package com.rag.backend.agent.repository;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.common.BizException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Primary
@Repository
public class MyBatisKnowledgeChunkRepository implements KnowledgeChunkRepository {
    private final KnowledgeChunkMapper mapper;

    public MyBatisKnowledgeChunkRepository(KnowledgeChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeChunk save(KnowledgeChunk chunk) {
        mapper.insert(chunk);
        return chunk;
    }

    @Override
    public KnowledgeChunk findById(long id) {
        return mapper.selectById(id);
    }

    @Override
    public void deleteByDocumentId(long documentId) {
        mapper.deleteByDocumentId(documentId);
    }

    @Override
    public void deleteByCourseId(long courseId) {
        mapper.deleteByCourseId(courseId);
    }

    @Override
    public void updateVectorStatus(Long chunkId, String milvusVectorId, String embeddingStatus) {
        KnowledgeChunk chunk = mapper.selectById(chunkId);
        if (chunk == null) {
            throw new BizException(400, "Knowledge chunk does not exist, chunkId=" + chunkId);
        }
        chunk.setMilvusVectorId(milvusVectorId);
        chunk.setEmbeddingStatus(embeddingStatus);
        mapper.updateVectorStatus(chunk);
    }
}
