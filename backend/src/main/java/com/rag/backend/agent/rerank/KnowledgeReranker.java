package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

// 只重排已经召回的候选并裁剪到 topK，不能补回召回阶段遗漏的文档。
public interface KnowledgeReranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK);

    /**
     * 新主链路使用带诊断的结果；旧实现与测试仍可只实现 {@link #rerank}。
     */
    default RerankExecutionResult rerankWithResult(
            String query,
            List<RetrievedChunk> chunks,
            int topK) {
        long started = System.nanoTime();
        List<RetrievedChunk> output = rerank(query, chunks, topK);
        return RerankExecutionResult.unobserved(
                chunks, output, System.nanoTime() - started);
    }
}
