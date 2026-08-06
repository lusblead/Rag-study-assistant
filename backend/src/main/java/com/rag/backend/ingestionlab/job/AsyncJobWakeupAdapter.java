package com.rag.backend.ingestionlab.job;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AsyncJobWakeupAdapter implements JobWakeupPort{
    private final TaskExecutor taskExecutor;
    private final DurableJobWorker durableJobWorker;
    public AsyncJobWakeupAdapter(
            @Qualifier("ingestionJobExecutor") TaskExecutor taskExecutor,
            DurableJobWorker durableJobWorker) {
        this.taskExecutor = taskExecutor;
        this.durableJobWorker = durableJobWorker;
    }
    @Override
    public void wakeup(String jobId){
        taskExecutor.execute(() -> {
            durableJobWorker.run(jobId);
        });
    }
}
