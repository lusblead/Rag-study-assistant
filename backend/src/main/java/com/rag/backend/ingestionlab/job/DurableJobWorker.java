package com.rag.backend.ingestionlab.job;

import com.rag.backend.ingestionlab.delete.DeleteJobWorker;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.springframework.stereotype.Component;

/** 根据持久 job_type 把同一个 jobId 路由到对应 Worker，不创建第二套内存任务身份。 */
@Component
public class DurableJobWorker {
    private final IngestJobMapper jobs;
    private final IngestJobWorker ingestWorker;
    private final DeleteJobWorker deleteWorker;
    private final TraceContextService traces;

    public DurableJobWorker(
            IngestJobMapper jobs,
            IngestJobWorker ingestWorker,
            DeleteJobWorker deleteWorker,
            TraceContextService traces) {
        this.jobs = jobs;
        this.ingestWorker = ingestWorker;
        this.deleteWorker = deleteWorker;
        this.traces = traces;
    }

    public void run(String jobId) {
        IngestJob job = jobs.selectById(jobId);
        if (job == null) {
            try (TraceSpan span = traces.startSpan(
                    "ingestion.durable.route", jobId)) {
                span.result("job_not_found");
            }
            return;
        }
        if (traces.currentCarrier().isEmpty()) {
            try (TraceSpan span = traces.continueOrStart(
                    job.traceCarrier(), "ingestion.job.recovery", jobId)) {
                try {
                    routeTraced(job);
                    span.result(job.getSubmitTraceparent() == null
                            ? "legacy_recovery" : "recovered");
                } catch (RuntimeException | Error error) {
                    span.error(error).result("failed");
                    throw error;
                }
            }
            return;
        }
        routeTraced(job);
    }

    private void routeTraced(IngestJob job) {
        try (TraceSpan span = traces.startSpan(
                "ingestion.durable.route", job.getJobId())) {
            try {
                route(job);
                span.result("dispatched");
            } catch (RuntimeException | Error error) {
                span.error(error).result("failed");
                throw error;
            }
        }
    }

    private void route(IngestJob job) {
        switch (job.getJobType()) {
            case "INGEST" -> ingestWorker.run(job.getJobId());
            case "DELETE" -> deleteWorker.run(job.getJobId());
            default -> throw new IllegalArgumentException(
                    "Unsupported durable job type: " + job.getJobType());
        }
    }
}
