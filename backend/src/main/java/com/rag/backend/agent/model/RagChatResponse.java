package com.rag.backend.agent.model;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

// 承载 RAG 问答接口的非流式响应。
public record RagChatResponse(Long sessionId, String answer, List<RetrievedChunk> references) {
    public RagChatResponse(String answer, List<RetrievedChunk> references) {
        this(null, answer, references);
    }
}
