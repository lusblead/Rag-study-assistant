// 用确定性 ID 让 MySQL 与向量库在重放后收敛。
package com.rag.backend.ingestionlab.vector;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// ConsistentVectorStore 让 Stage 指定确定性主键，并给 Verifier/Reconciler 提供按 Version 核验与删除能力。
public interface ConsistentVectorStore {
    void upsert(VectorRecord record);
    void awaitVersionVisible(long documentVersionId,
                             int expectedCount,
                             Duration timeout);
    Optional<VectorMetadata> find(long vectorId);
    long countByVersion(long documentVersionId);
    Set<Long> listIdsByVersion(long documentVersionId);
    void deleteByVersion(long documentVersionId);

    // 写入载荷携带完整业务身份，重放查询时才能证明该 ID 属于本次 Chunk，而不只是“有一条记录”。
    record VectorRecord(long vectorId, long mysqlChunkId, long courseId,
                        long documentId, long documentVersionId,
                        String chunkBusinessKey, String contentHash,
                        String embeddingModel, int dimension,
                        List<Double> embedding) { }

    // find 返回可核验元数据；裸 exists 不能发现 64 位 ID 碰撞或模型身份错误。
    record VectorMetadata(long vectorId, long mysqlChunkId,
                          long documentVersionId,
                          String chunkBusinessKey, String contentHash,
                          String embeddingModel, int dimension) { }
}
