package com.rag.backend.ingestionlab.job;

public interface JobWakeupPort {
    public void wakeup(String jobId);
}
