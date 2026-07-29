package com.rag.backend.agent.repository;
import com.rag.backend.agent.model.KnowledgeChunk;

import java.util.List;

// 持久化知识片段元数据和外部向量 ID/状态；实际向量内容由 VectorStoreService 管理。
public interface KnowledgeChunkRepository {
    KnowledgeChunk save(KnowledgeChunk chunk);

    KnowledgeChunk findById(long id);

    List<KnowledgeChunk> findByCourseId(long courseId, int limit);

    void deleteByDocumentId(long documentId);

    void deleteByCourseId(long courseId);

    void updateVectorStatus(Long chunkId,String milvusVectorId,String embeddingStatus);
}
