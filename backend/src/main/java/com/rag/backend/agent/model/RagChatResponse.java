package com.rag.backend.agent.model;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

public record RagChatResponse(String answer, List<RetrievedChunk> references) {
}
