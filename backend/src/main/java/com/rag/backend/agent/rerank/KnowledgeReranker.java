package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

// 定义知识片段重排序接口。
public interface KnowledgeReranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK);
}
