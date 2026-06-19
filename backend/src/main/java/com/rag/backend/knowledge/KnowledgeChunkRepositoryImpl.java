package com.rag.backend.knowledge;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Repository;

/**
 * KnowledgeChunkRepository 的 MyBatis 实现。
 * 放在 knowledge 包而非 agent 包，遵循模块边界约定。
 */
@Repository
public class KnowledgeChunkRepositoryImpl implements KnowledgeChunkRepository {

    private final SqlSession sqlSession;

    private static final String NAMESPACE = "com.rag.backend.knowledge.KnowledgeChunkMapper";

    public KnowledgeChunkRepositoryImpl(SqlSession sqlSession) {
        this.sqlSession = sqlSession;
    }

    @Override
    public KnowledgeChunk save(KnowledgeChunk chunk) {
        sqlSession.insert(NAMESPACE + ".insert", chunk);
        return chunk;
    }

    @Override
    public KnowledgeChunk findById(long id) {
        return sqlSession.selectOne(NAMESPACE + ".selectById", id);
    }

    @Override
    public void updateVectorStatus(Long chunkId, String milvusVectorId, String embeddingStatus) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(chunkId);
        chunk.setMilvusVectorId(milvusVectorId);
        chunk.setEmbeddingStatus(embeddingStatus);
        sqlSession.update(NAMESPACE + ".updateVectorStatus", chunk);
    }
}
