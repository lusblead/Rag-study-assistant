package com.rag.backend.agent.retrieval.diversity;

import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;
import java.util.Objects;

/**
 * Rerank 后、FinalK 前的有界多样性选择边界。
 * 实现只能从输入候选中选择，不能召回或创建新的 Chunk。
 */
public interface DiversitySelector {

    boolean enabled();

    DiversitySelectionResult select(
            List<RetrievedChunk> rankedCandidates,
            int finalK);

    static DiversitySelector disabled() {
        return new DiversitySelector() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public DiversitySelectionResult select(
                    List<RetrievedChunk> rankedCandidates,
                    int finalK) {
                Objects.requireNonNull(
                        rankedCandidates, "rankedCandidates");
                if (finalK <= 0) {
                    throw new IllegalArgumentException(
                            "FinalK must be > 0");
                }
                List<RetrievedChunk> output = rankedCandidates.stream()
                        .limit(finalK)
                        .toList();
                return new DiversitySelectionResult(
                        output,
                        RetrievalDiagnostics.Diversity.disabled(
                                rankedCandidates.size(), output.size()));
            }
        };
    }
}
