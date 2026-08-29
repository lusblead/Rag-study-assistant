package com.rag.backend.agent.model;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import reactor.core.publisher.Flux;

import java.util.List;

// 承载流式问答的会话、引用、元数据和响应流。
public record RagChatStreamResponse(
        Long sessionId,
        List<RetrievedChunk> references,
        RagChatMetadata metadata,
        Flux<String> stream) {
    public RagChatStreamResponse(
            Long sessionId,
            List<RetrievedChunk> references,
            Flux<String> stream) {
        this(sessionId, references, null, stream);
    }
}
