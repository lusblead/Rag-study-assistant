package com.rag.backend.ingestionlab.state;

// 逻辑文档生命周期只回答“该文档是否应对用户可见以及是否正在删除”。
public enum DocumentLifecycle {
    ACTIVE,
    DELETING,
    DELETED
}