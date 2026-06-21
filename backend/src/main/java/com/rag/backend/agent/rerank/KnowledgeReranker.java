package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

public interface KnowledgeReranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK);
}
