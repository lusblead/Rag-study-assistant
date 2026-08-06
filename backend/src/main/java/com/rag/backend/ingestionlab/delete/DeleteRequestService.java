package com.rag.backend.ingestionlab.delete;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.common.BizException;
import com.rag.backend.ingestionlab.job.IngestJob;
import com.rag.backend.ingestionlab.job.IngestJobMapper;
import com.rag.backend.ingestionlab.outbox.OutboxEvent;
import com.rag.backend.ingestionlab.outbox.OutboxEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** 先让文档不可见，再在同一事务登记可恢复的 DELETE Job 与 Outbox。 */
@Service
public class DeleteRequestService {
    private final DeleteRequestMapper documents;
    private final IngestJobMapper jobs;
    private final OutboxEventMapper outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DeleteRequestService(
            DeleteRequestMapper documents,
            IngestJobMapper jobs,
            OutboxEventMapper outbox,
            ObjectMapper objectMapper) {
        this(documents, jobs, outbox, objectMapper, Clock.systemUTC());
    }

    DeleteRequestService(
            DeleteRequestMapper documents,
            IngestJobMapper jobs,
            OutboxEventMapper outbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.documents = documents;
        this.jobs = jobs;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public Submission request(long documentId) {
        DeleteRequestMapper.DeleteDocumentRow document =
                documents.lockDocument(documentId);
        if (document == null) {
            throw new BizException(404, "Document does not exist: " + documentId);
        }

        IngestJob existing = jobs.selectLatestByDocumentAndType(
                documentId, "DELETE");
        if ("DELETING".equals(document.getLifecycleStatus())
                || "DELETED".equals(document.getLifecycleStatus())) {
            if (existing == null) {
                throw new IllegalStateException(
                        "Tombstoned document has no DELETE job: " + documentId);
            }
            return new Submission(
                    documentId, existing.getJobId(), true,
                    "DELETED".equals(document.getLifecycleStatus()));
        }
        if (!"ACTIVE".equals(document.getLifecycleStatus())) {
            throw new IllegalStateException(
                    "Unsupported document lifecycle: "
                            + document.getLifecycleStatus());
        }

        if (documents.tombstone(documentId) != 1) {
            throw new IllegalStateException(
                    "Cannot tombstone document: " + documentId);
        }
        // 与墓碑同一事务提交：先使在线内容不可见，再撤销旧 INGEST Worker 的提交权。
        documents.cancelIngestJobs(documentId);

        String jobId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant(), ZoneOffset.UTC);
        IngestJob job = new IngestJob();
        job.setJobId(jobId);
        job.setDocumentId(documentId);
        job.setDocumentVersionId(null);
        job.setJobType("DELETE");
        job.setMaxAttempts(20);
        job.setNextRunAt(now);
        jobs.insert(job);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType("DELETE_JOB");
        event.setAggregateId(jobId);
        event.setEventType("DOCUMENT_DELETE_REQUESTED");
        event.setPayloadJson(json(Map.of(
                "jobId", jobId,
                "documentId", documentId)));
        event.setAvailableAt(now);
        outbox.insert(event);
        return new Submission(documentId, jobId, false, false);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Cannot encode delete outbox", error);
        }
    }

    public record Submission(
            long documentId,
            String jobId,
            boolean reused,
            boolean alreadyDeleted) {
    }
}
