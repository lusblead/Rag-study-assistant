package com.rag.backend.agent.retrieval;

import java.util.List;
import java.util.Objects;

/** 一个候选源独立返回的不可变批次。 */
public record CandidateBatch(
        CandidateSourceType source,
        List<RetrievalCandidate> candidates) {
    public CandidateBatch {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(candidates, "candidates");
        candidates = List.copyOf(candidates);
        if (candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidates must not contain null");
        }
    }

    public static CandidateBatch empty(CandidateSourceType source) {
        return new CandidateBatch(source, List.of());
    }
}
