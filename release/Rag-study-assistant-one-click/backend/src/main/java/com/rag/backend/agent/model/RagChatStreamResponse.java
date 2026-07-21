package com.rag.backend.agent.model;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import reactor.core.publisher.Flux;

import java.util.List;

public record RagChatStreamResponse(Long sessionId, List<RetrievedChunk> references, Flux<String> stream) {
}
