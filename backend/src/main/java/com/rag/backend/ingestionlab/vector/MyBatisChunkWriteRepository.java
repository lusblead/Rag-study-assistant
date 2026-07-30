// chunk 协议：versionId 与 businessKey 唯一，重放回读同一行再继续向量写入。
package com.rag.backend.ingestionlab.vector;

import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;

@Repository
// MyBatis 实现把 Repository 协议映射到唯一键插入、碰撞校验和状态条件更新。
public class MyBatisChunkWriteRepository implements ChunkWriteRepository {
    // Mapper 是 Chunk 行的唯一写入口；Repository 只把 duplicate-key 解释为可能重放，再回读完整身份。
    private final IdempotentChunkMapper mapper;

    // Repository 只依赖 Mapper，跨存储顺序仍由上层 VectorWriteStage 编排。
    public MyBatisChunkWriteRepository(IdempotentChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    // 普通 INSERT 让非重复键错误显式失败；只有 duplicate-key 才进入回读核验。
    public ChunkRow getOrCreate(ChunkDraft draft) {
        try {
            mapper.insert(draft);
        } catch (DuplicateKeyException duplicate) {
            // 并发重放会命中唯一键；其他 SQL 错误不会被 INSERT IGNORE 一并吞掉。
        }
        var row = mapper.find(draft.documentVersionId(), draft.businessKey());
        if (row == null) throw new IllegalStateException("Chunk insert/select lost");
        if (!draft.contentHash().equals(row.getContentHash())) {
            throw new IllegalStateException("Business key collision with different content");
        }
        return new ChunkRow(row.getId(), row.getDocumentVersionId(),
                row.getChunkBusinessKey(), row.getContentHash(), row.getStatus(),
                row.getVectorId(), row.getEmbeddingModel());
    }

    @Override
    public void reserveVectorIdentity(long chunkId, long vectorId,
                                      String embeddingModel, int dimension) {
        try {
            if (mapper.reserveVectorIdentity(
                    chunkId, vectorId, embeddingModel, dimension) != 1) {
                throw new IllegalStateException(
                        "Vector identity conflict: " + chunkId);
            }
        } catch (DuplicateKeyException collision) {
            // vector_business_id 唯一键在远端写之前拒绝 64 位碰撞。
            throw new IllegalStateException(
                    "VECTOR_ID_COLLISION:" + vectorId, collision);
        }
    }

    @Override
    // 保存 DONE/FAILED 证据和稳定错误码。
    public void markDone(long chunkId, long vectorId, String embeddingModel) {
        if (mapper.markDone(chunkId, vectorId, embeddingModel) != 1) {
            throw new IllegalStateException("Chunk DONE conflict: " + chunkId);
        }
    }

    @Override
    // 保存 DONE/FAILED 证据和稳定错误码。
    public void markFailed(long chunkId, String errorCode) {
        mapper.markFailed(chunkId, errorCode);
    }
}