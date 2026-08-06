package com.rag.backend.ingestionlab.job;

import com.rag.backend.ingestionlab.delete.DeleteJobWorker;
import org.springframework.stereotype.Component;

/** 根据持久 job_type 把同一个 jobId 路由到对应 Worker，不创建第二套内存任务身份。 */
@Component
public class DurableJobWorker {
    private final IngestJobMapper jobs;
    private final IngestJobWorker ingestWorker;
    private final DeleteJobWorker deleteWorker;

    public DurableJobWorker(
            IngestJobMapper jobs,
            IngestJobWorker ingestWorker,
            DeleteJobWorker deleteWorker) {
        this.jobs = jobs;
        this.ingestWorker = ingestWorker;
        this.deleteWorker = deleteWorker;
    }

    public void run(String jobId) {
        IngestJob job = jobs.selectById(jobId);
        if (job == null) {
            return;
        }
        switch (job.getJobType()) {
            case "INGEST" -> ingestWorker.run(jobId);
            case "DELETE" -> deleteWorker.run(jobId);
            default -> throw new IllegalArgumentException(
                    "Unsupported durable job type: " + job.getJobType());
        }
    }
}
