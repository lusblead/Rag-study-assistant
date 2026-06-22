package com.rag.backend.agent.model;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import reactor.core.publisher.Flux;

import java.util.List;

// 承载流式问答的会话、引用和模型流。
public record RagChatStreamResponse(Long sessionId, List<RetrievedChunk> references, Flux<String> stream) {
}
