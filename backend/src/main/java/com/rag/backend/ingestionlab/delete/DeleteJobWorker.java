package com.rag.backend.ingestionlab.delete;

import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.job.IngestJob;
import com.rag.backend.ingestionlab.job.IngestJobMapper;
import com.rag.backend.ingestionlab.job.JobLeaseService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/** 取得 DELETE Job Lease 后重放同一个 Delete Saga；物理删除失败不会撤销墓碑。 */
@Service
public class DeleteJobWorker {
    private final JobLeaseService leases;
    private final IngestJobMapper jobs;
    private final DeleteSaga saga;
    private final String owner = "delete-worker-" + UUID.randomUUID();

    public DeleteJobWorker(
            JobLeaseService leases,
            IngestJobMapper jobs,
            DeleteSaga saga) {
        this.leases = leases;
        this.jobs = jobs;
        this.saga = saga;
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
            IngestJob job = jobs.selectById(jobId);
            if (job == null || !"DELETE".equals(job.getJobType())) {
                leases.fail(session.current(), "DELETE_JOB_INVALID",
                        StableHash.sha256("DELETE_JOB_INVALID"));
                return;
            }
            // 删除可能跨越多个存储，进入 Saga 前后都续租；任一次续租失败，
            // 旧 Worker 都停止提交，后续实例从同一墓碑和 jobId 继续重放。
            session.renew();
            saga.execute(job.getDocumentId());
            session.renew();
            leases.succeed(session.current());
        } catch (com.rag.backend.agent.materials.MaterialReclamationService.ReadersActiveException waiting) {
            leases.deferDeleteForReaders(session.current());
        } catch (JobLeaseService.LeaseLostException ignored) {
            // 新 owner 会从仍然存在的墓碑继续清理。
        } catch (IllegalArgumentException error) {
            leases.fail(session.current(), "DELETE_VALIDATION",
                    StableHash.sha256(error.getClass().getName()));
        } catch (RuntimeException error) {
            leases.retry(session.current(), "DELETE_DEPENDENCY_FAILED",
                    StableHash.sha256(error.getClass().getName()),
                    Duration.ofSeconds(30));
        }
    }
}
