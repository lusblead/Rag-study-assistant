package com.rag.backend.ingestionlab.state;

// Step 状态描述一个 Job 内的 PARSE、CHUNK、VECTOR 等步骤是否已有可复用输出。
public enum IngestStepState {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}