package com.rag.backend.agent.retrieval.diversity;

import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;
import java.util.Objects;

public record DiversitySelectionResult(
        List<RetrievedChunk> chunks,
        RetrievalDiagnostics.Diversity diagnostics
) {
    public DiversitySelectionResult {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }
}
