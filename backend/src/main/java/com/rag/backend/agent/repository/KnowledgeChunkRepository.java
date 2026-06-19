package com.rag.backend.agent.repository;
import com.rag.backend.agent.model.KnowledgeChunk;
public interface KnowledgeChunkRepository {
    KnowledgeChunk save(KnowledgeChunk chunk);

    KnowledgeChunk findById(long id);

    void deleteByDocumentId(long documentId);

    void updateVectorStatus(Long chunkId,String milvusVectorId,String embeddingStatus);
}
