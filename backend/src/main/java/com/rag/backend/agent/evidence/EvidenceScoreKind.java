package com.rag.backend.agent.evidence;

/** 分数来源不同，不能共享一个未经校准的统一门槛。 */
public enum EvidenceScoreKind {
    REMOTE_RERANK,
    LOCAL_RERANK,
    FUSION,
    DENSE,
    LEXICAL,
    LEGACY_FINAL,
    NONE
}
