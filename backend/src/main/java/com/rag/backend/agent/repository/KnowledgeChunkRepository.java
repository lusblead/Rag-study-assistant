package com.rag.backend.agent.repository;
import com.rag.backend.agent.model.KnowledgeChunk;

import java.util.List;

// 定义知识片段持久化仓储接口。
public interface KnowledgeChunkRepository {
    KnowledgeChunk save(KnowledgeChunk chunk);

    KnowledgeChunk findById(long id);

    List<KnowledgeChunk> findByCourseId(long courseId, int limit);

    void deleteByDocumentId(long documentId);

    void deleteByCourseId(long courseId);

    void updateVectorStatus(Long chunkId,String milvusVectorId,String embeddingStatus);
}
