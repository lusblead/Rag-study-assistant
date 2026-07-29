package com.rag.backend.ingestionlab.state;

// Version 状态描述不可变内容版本的构建、验证、激活和修复过程。
// QUEUED、RUNNING、RETRY_WAIT 属于 Job，不能放进这里。
public enum DocumentVersionState {
    UPLOADED,
    BUILDING,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    VERIFYING,
    READY,
    ACTIVE,
    INCONSISTENT,
    REPAIRING,
    SUPERSEDED,
    FAILED,
    CANCELLED;

    // 仅 Version 为 ACTIVE 仍不够；检索还必须确认所属 DocumentLifecycle 为 ACTIVE。
    public boolean isVersionReadable() {
        return this == ACTIVE;
    }

    // SUPERSEDED 仍可能在回滚窗口内恢复，因此不把它视为不可逆终态。
    public boolean isTerminal() {
        return this == FAILED || this == CANCELLED;
    }
}