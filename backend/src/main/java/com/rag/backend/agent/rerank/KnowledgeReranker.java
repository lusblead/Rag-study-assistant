package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

// 只重排已经召回的候选并裁剪到 topK，不能补回召回阶段遗漏的文档。
public interface KnowledgeReranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK);
}
