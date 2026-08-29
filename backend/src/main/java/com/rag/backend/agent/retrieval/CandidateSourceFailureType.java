package com.rag.backend.agent.retrieval;

/** 双源收集阶段的有界失败分类。 */
public enum CandidateSourceFailureType {
    TIMEOUT,
    INVALID_BATCH,
    SOURCE_ERROR
}
