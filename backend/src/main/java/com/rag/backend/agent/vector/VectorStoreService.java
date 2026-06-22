package com.rag.backend.agent.vector;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;

import java.util.List;

// 定义向量存储服务的统一接口。
public interface VectorStoreService {
    String upsert(KnowledgeChunk chunk, List<Double> embedding);

    List<VectorSearchResult> search(Long courseId,List<Double> queryVector,int topK);

    void deleteByDocumentId(Long documentId);

    void deleteByCourseId(Long courseId);
}
