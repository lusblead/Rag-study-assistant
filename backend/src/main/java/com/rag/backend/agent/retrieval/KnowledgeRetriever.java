package com.rag.backend.agent.retrieval;

import java.util.List;

// 在指定课程范围内返回最多 topK 条候选证据，防止跨课程召回；生成与引用校验由上层完成。
public interface KnowledgeRetriever {
    List<RetrievedChunk> retrieve(Long courseId,String query,int topK);
}
