package com.rag.backend.agent.retrieval;

/** 单个 source 的延迟、候选数和失败诊断。 */
public record CandidateSourceDiagnostic(
        CandidateSourceType source,
        CandidateSourceType failedSource,
        CandidateSourceFailureType failureType,
        long sourceLatencyNanos,
        int candidateCount) {
    public boolean succeeded() {
        return failedSource == null;
    }
}
