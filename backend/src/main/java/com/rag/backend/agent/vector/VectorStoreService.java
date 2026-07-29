package com.rag.backend.agent.vector;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;

import java.util.List;

// 隔离向量 Provider：upsert 返回外部向量 ID，search 必须按课程过滤，删除入口负责文档/课程级清理。
public interface VectorStoreService {
    String upsert(KnowledgeChunk chunk, List<Double> embedding);

    List<VectorSearchResult> search(Long courseId,List<Double> queryVector,int topK);

    void deleteByDocumentId(Long documentId);

    void deleteByCourseId(Long courseId);
}
