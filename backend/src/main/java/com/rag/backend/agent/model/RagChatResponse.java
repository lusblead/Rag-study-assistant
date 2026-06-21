package com.rag.backend.agent.model;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

public record RagChatResponse(Long sessionId, String answer, List<RetrievedChunk> references) {
    public RagChatResponse(String answer, List<RetrievedChunk> references) {
        this(null, answer, references);
    }
}
