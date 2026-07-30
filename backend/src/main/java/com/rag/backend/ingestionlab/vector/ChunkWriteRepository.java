// chunk 协议：versionId 与 businessKey 唯一，重放回读同一行再继续向量写入。
package com.rag.backend.ingestionlab.vector;

// ChunkWriteRepository 固定 MySQL Chunk 的 get-or-create 与 DONE/FAILED 更新协议，供向量 Stage 重放。
public interface ChunkWriteRepository {
    ChunkRow getOrCreate(ChunkDraft draft);
    void reserveVectorIdentity(long mysqlChunkId, long vectorId,
                               String embeddingModel, int dimension);
    void markDone(long mysqlChunkId, long vectorId, String embeddingModel);
    void markFailed(long mysqlChunkId, String errorCode);

    // ChunkDraft：向量写阶段的输入，固定文档归属、业务键、内容与模型身份。
    record ChunkDraft(long courseId, long documentId, long documentVersionId,
                      int chunkIndex, String title, String content,
                      Integer sourcePage, int tokenCount,
                      String businessKey, String contentHash) { }

    // ChunkRow：数据库映射模型，保存可恢复协议的持久化事实。
    record ChunkRow(long id, long documentVersionId, String businessKey,
                    String contentHash, String status,
                    Long vectorId, String embeddingModel) { }
}