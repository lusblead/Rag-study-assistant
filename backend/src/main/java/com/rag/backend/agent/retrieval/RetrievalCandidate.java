package com.rag.backend.agent.retrieval;

import java.util.Objects;

/**
 * 来源内部候选。stableOrder 是来源给出的稳定顺序标记，不是 RRF rank。
 */
public record RetrievalCandidate(
        RetrievedChunk chunk,
        double rawScore,
        long stableOrder) {
    public RetrievalCandidate {
        Objects.requireNonNull(chunk, "chunk");
        if (!Double.isFinite(rawScore)) {
            throw new IllegalArgumentException("rawScore must be finite");
        }
        if (stableOrder < 0) {
            throw new IllegalArgumentException("stableOrder must be >= 0");
        }
    }
}
