package com.rag.backend.ingestionlab.job;

import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.state.DocumentVersionState;
import com.rag.backend.ingestionlab.state.DocumentVersionStateMachine;
import com.rag.backend.ingestionlab.vector.VectorFailureClassifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 领取持久 INGEST Job，执行编排，并按稳定错误分类提交终态或退避。 */
@Service
public class IngestJobWorker {
    private final JobLeaseService leases;
    private final IngestJobOrchestrator orchestrator;
    private final IngestJobMapper jobs;
    private final DocumentVersionMapper versions;
    private final String owner;

    public IngestJobWorker(
            JobLeaseService leases,
            IngestJobOrchestrator orchestrator,
            IngestJobMapper jobs,
            DocumentVersionMapper versions) {
        this.leases = leases;
        this.orchestrator = orchestrator;
        this.jobs = jobs;
        this.versions = versions;
        this.owner = "ingest-worker-" + UUID.randomUUID();
    }

    public void run(String jobId) {
        JobLeaseService.Lease lease;
        try {
            lease = leases.claim(jobId, owner);
        } catch (JobLeaseService.LeaseNotAcquiredException ignored) {
            return;
        }

        JobLeaseService.LeaseSession session = leases.openSession(lease);
        try {
            orchestrator.runOwned(session);
            leases.succeed(session.current());
        } catch (JobLeaseService.LeaseLostException ignored) {
            // 旧 owner 已无提交权；新 Worker 将从持久状态接管。
        } catch (RuntimeException error) {
            try {
                Failure failure = classify(error);
                if (failure.permanent()) {
                    markVersionFailed(jobId, error, session.current());
                    leases.fail(session.current(), failure.code(),
                            safeDigest(error, failure.code()));
                } else {
                    leases.retry(
                            session.current(),
                            failure.code(),
                            safeDigest(error, failure.code()),
                            failure.backoff());
                }
            } catch (JobLeaseService.LeaseLostException ignored) {
                // 错误处理本身也受 Lease 约束；失去所有权后不再写 Version 或 Job。
            }
        }
    }

    private Failure classify(RuntimeException error) {
        if (error instanceof IngestJobOrchestrator.SourceIdentityMismatchException) {
            return Failure.permanent("SOURCE_IDENTITY_MISMATCH");
        }
        if (error instanceof IngestJobOrchestrator.PipelineIdentityMismatchException) {
            return Failure.permanent("PIPELINE_IDENTITY_MISMATCH");
        }
        if (error instanceof IngestJobOrchestrator.DocumentTombstonedException) {
            return Failure.permanent("DOCUMENT_DELETING");
        }
        if (error instanceof IngestJobOrchestrator.InconsistentIndexException) {
            return Failure.permanent("INDEX_INCONSISTENT");
        }
        if (error instanceof IngestJobOrchestrator.UnsupportedResumeStateException
                || error instanceof IllegalArgumentException) {
            return Failure.permanent("INGEST_STATE_INVALID");
        }

        VectorFailureClassifier.VectorFailure vector =
                VectorFailureClassifier.classify(error);
        if (!vector.retryable() && !vector.unknown()) {
            return Failure.permanent(vector.errorCode());
        }
        long requested = Math.max(vector.retryAfterMs(), 30_000L);
        return Failure.retryable(vector.errorCode(), Duration.ofMillis(requested));
    }

    private void markVersionFailed(
            String jobId,
            RuntimeException error,
            JobLeaseService.Lease lease) {
        if (error instanceof IngestJobOrchestrator.InconsistentIndexException) {
            return;
        }
        IngestJob job = jobs.selectById(jobId);
        if (job == null || job.getDocumentVersionId() == null) {
            return;
        }
        DocumentVersionRow version = versions.findById(job.getDocumentVersionId());
        if (version == null) {
            return;
        }
        DocumentVersionState state = DocumentVersionState.valueOf(version.getState());
        if (DocumentVersionStateMachine.canTransition(
                state, DocumentVersionState.FAILED)) {
            int changed = versions.failIfJobOwned(
                    version.getId(),
                    state.name(),
                    version.getStateVersion(),
                    jobId,
                    lease.owner(),
                    lease.stateVersion(),
                    LocalDateTime.now(ZoneOffset.UTC));
            if (changed != 1) {
                throw new JobLeaseService.LeaseLostException(jobId);
            }
        }
    }

    private String safeDigest(RuntimeException error, String code) {
        return StableHash.sha256(
                error.getClass().getName() + "|" + code);
    }

    private record Failure(
            String code,
            boolean permanent,
            Duration backoff) {
        static Failure permanent(String code) {
            return new Failure(code, true, Duration.ZERO);
        }

        static Failure retryable(String code, Duration backoff) {
            return new Failure(code, false, backoff);
        }
    }
}
