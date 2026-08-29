package com.rag.backend.agent.evidence;

import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;
import java.util.Objects;

/** Evidence Sufficiency Policy 的完整输入，不包含待生成答案。 */
public record EvidenceDecisionInput(
        String originalQuestion,
        List<ChatMessage> history,
        List<RetrievedChunk> chunks,
        EvidenceConstraints constraints,
        RetrievalDiagnostics retrievalDiagnostics
) {
    public EvidenceDecisionInput {
        if (originalQuestion == null || originalQuestion.isBlank()) {
            throw new IllegalArgumentException("originalQuestion is required");
        }
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        constraints = Objects.requireNonNull(constraints, "constraints");
        retrievalDiagnostics = Objects.requireNonNull(
                retrievalDiagnostics, "retrievalDiagnostics");
    }
}
