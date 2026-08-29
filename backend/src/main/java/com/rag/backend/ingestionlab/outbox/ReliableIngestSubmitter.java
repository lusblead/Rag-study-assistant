// 持久化执行权，用 owner、期限和版本阻止并发误提交。 让业务状态与事件原子落库，并为步骤建立幂等记录。
package com.rag.backend.ingestionlab.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.ingestionlab.job.IngestJob;
import com.rag.backend.ingestionlab.job.IngestJobMapper;
import com.rag.backend.ingestionlab.identity.PipelineManifest;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
// 可靠受理：同事务创建 Version、Job 与 Outbox。
public class ReliableIngestSubmitter {
    private final DocumentVersionMapper versionMapper;
    private final IngestJobMapper jobMapper;
    private final OutboxEventMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final TraceContextService traces;
    // 注入时钟使租约过期可确定性测试。
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    // Spring 构造器使用 UTC 生成 Job/事件时间；包内构造器允许事务测试固定时钟。
    public ReliableIngestSubmitter(DocumentVersionMapper versionMapper,
                                   IngestJobMapper jobMapper,
                                   OutboxEventMapper outboxMapper,
                                   ObjectMapper objectMapper,
                                   TraceContextService traces) {
        this(versionMapper, jobMapper, outboxMapper, objectMapper,
                traces, Clock.systemUTC());
    }

    ReliableIngestSubmitter(DocumentVersionMapper versionMapper,
                            IngestJobMapper jobMapper,
                            OutboxEventMapper outboxMapper,
                            ObjectMapper objectMapper,
                            TraceContextService traces,
                            Clock clock) {
        this.versionMapper = versionMapper;
        this.jobMapper = jobMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.traces = traces;
        this.clock = clock;
    }

    @Transactional
    // 事务内去重/建版本，并原子登记 Job 与 Outbox。
    public Submission submit(long documentId, String contentHash,
                             String pipelineFingerprint) {
        return submit(documentId, null, contentHash, pipelineFingerprint,
                null, 1);
    }

    /**
     * 可信入口使用的完整受理方法。可读 Manifest 与其指纹同时落库，后续 Worker
     * 能拒绝“同一个 Version 却换了处理语义”的重放。
     */
    @Transactional
    public Submission submit(long documentId,
                             String sourceRef,
                             String contentHash,
                             PipelineManifest manifest) {
        return submit(documentId, sourceRef, contentHash,
                manifest.fingerprint().value(), json(manifest), 1);
    }

    private Submission submit(long documentId,
                              String sourceRef,
                              String contentHash,
                              String pipelineFingerprint,
                              String pipelineManifest,
                              int manifestSchemaVersion) {
        try (TraceSpan span = traces.startSpan("ingestion.submit")) {
            try {
                Submission result = submitWithinTrace(
                        documentId, sourceRef, contentHash,
                        pipelineFingerprint, pipelineManifest,
                        manifestSchemaVersion);
                // target 返回后 Spring 事务代理才会 commit；此处只能证明已准备完毕。
                span.result(result.reused() ? "reused" : "prepared");
                return result;
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private Submission submitWithinTrace(long documentId,
                                         String sourceRef,
                                         String contentHash,
                                         String pipelineFingerprint,
                                         String pipelineManifest,
                                         int manifestSchemaVersion) {
        DocumentVersionMapper.LockedDocumentRow document =
                versionMapper.lockDocument(documentId);
        if (document == null) {
            throw new IllegalArgumentException("Unknown document: " + documentId);
        }
        if (!"ACTIVE".equals(document.getLifecycleStatus())) {
            throw new IllegalStateException(
                    "Document is not ingestible: " + document.getLifecycleStatus());
        }
        DocumentVersionRow existing = versionMapper.findIdentity(
                documentId, contentHash, pipelineFingerprint);
        if (existing != null) {
            // Version 与 INGEST Job 在同一事务创建；复用时必须返回同一个可查询 jobId。
            IngestJob existingJob = jobMapper.selectByVersionAndType(
                    existing.getId(), "INGEST");
            if (existingJob == null) {
                // 这不是“重新建一个 Job”即可掩盖的问题，而是原子受理不变量已被破坏。
                throw new IllegalStateException(
                        "Version exists without INGEST job: " + existing.getId());
            }
            // 请求级 submit span 不冒充既有 Job；短子 span 只覆盖 correlationId，
            // parent 仍是当前请求，并通过 link 指向首次提交的持久 Trace。
            try (TraceSpan reused = traces.startSpan(
                    "ingestion.submit.reuse", existingJob.getJobId())) {
                traces.link(existingJob.traceCarrier(), "ingestion.submit.reused");
                reused.result("reused");
            }
            return new Submission(existing.getId(),
                    existingJob.getJobId(), true);
        }

        DocumentVersionRow version = new DocumentVersionRow();
        version.setDocumentId(documentId);
        version.setVersionNo(versionMapper.nextVersionNo(documentId));
        version.setContentHash(contentHash);
        version.setPipelineFingerprint(pipelineFingerprint);
        version.setSourceRef(sourceRef);
        version.setPipelineManifest(pipelineManifest);
        version.setManifestSchemaVersion(manifestSchemaVersion);
        version.setState("UPLOADED");
        version.setStateVersion(0L);
        versionMapper.insert(version);

        String jobId = UUID.randomUUID().toString();
        TraceCarrier carrier = traces.capture(jobId);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        IngestJob job = new IngestJob();
        job.setJobId(jobId);
        job.setDocumentId(documentId);
        job.setDocumentVersionId(version.getId());
        job.setJobType("INGEST");
        job.setMaxAttempts(5);
        job.setNextRunAt(now);
        job.setSubmitTraceparent(carrier.traceparent());
        jobMapper.insert(job);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType("INGEST_JOB");
        event.setAggregateId(jobId);
        event.setEventType("INGEST_REQUESTED");
        event.setPayloadJson(json(IngestRequestedEventPayload.current(
                jobId, version.getId(), carrier)));
        event.setAvailableAt(now);
        outboxMapper.insert(event);

        if (versionMapper.transition(version.getId(), "UPLOADED", "BUILDING", 0L) != 1) {
            throw new IllegalStateException("Cannot start building version " + version.getId());
        }
        return new Submission(version.getId(), jobId, false);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        // Outbox payload 编码失败会抛出并触发整个受理事务回滚，原始 Jackson 异常保留为 cause。
        catch (Exception e) { throw new IllegalStateException("Cannot encode outbox", e); }
    }

    // Submission 是 Controller/Tool 的受理结果：关联 Version、可查询 Job，并说明是否复用了既有任务。
    public record Submission(long documentVersionId, String jobId, boolean reused) { }
}
