package com.rag.backend.agent.retrieval;

import java.util.List;

// 在指定课程范围内返回最多 topK 条候选证据，防止跨课程召回；生成与引用校验由上层完成。
public interface KnowledgeRetriever {
    /** Only a server-confirmed scope may reach this entry; unsupported implementations fail closed. */
    default RetrievalExecutionResult retrieveInScope(RetrievalScope scope, String query, int topK) {
        throw new UnsupportedOperationException("Retriever does not support a pinned session scope");
    }
    List<RetrievedChunk> retrieve(Long courseId,String query,int topK);

    /**
     * 问答主链路使用带诊断的结果；旧实现、评测和题目生成仍可只实现 {@link #retrieve}。
     */
    default RetrievalExecutionResult retrieveWithResult(
            Long courseId,
            String query,
            int topK) {
        return RetrievalExecutionResult.unobserved(
                retrieve(courseId, query, topK));
    }
}
