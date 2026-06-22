package com.rag.backend.agent.model;

// 表示向量检索返回的片段 ID 和分数。
public record VectorSearchResult(Long chunkId,Double score) {
}
