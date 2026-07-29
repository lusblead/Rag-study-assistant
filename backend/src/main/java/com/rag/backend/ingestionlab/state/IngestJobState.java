package com.rag.backend.ingestionlab.state;

// Job 状态描述持久化任务的调度生命周期；Lease owner 和 leaseUntil 是它的执行权字段。
public enum IngestJobState {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED
}