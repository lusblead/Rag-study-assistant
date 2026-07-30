// 用确定性 ID 让 MySQL 与向量库在重放后收敛。
package com.rag.backend.ingestionlab.vector;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// 模拟 Milvus 已提交但响应丢失，证明重放仍使用同一 vectorId 且最终只有一个逻辑向量。
class VectorWriteStageTest {

    @Test
        // 验证重放读取制品而不重复昂贵调用。
    void responseLossThenReplayStillCreatesOneLogicalVector() {
        InMemoryChunks mysql = new InMemoryChunks();
        LostFirstResponseVectors milvus = new LostFirstResponseVectors();
        VectorWriteStage stage = new VectorWriteStage(mysql,
                text -> List.of(1.0, 0.0), milvus, "test-model", 2);
        var draft = new ChunkWriteRepository.ChunkDraft(
                1L, 10L, 99L, 0, "title", "content", 1, 7,
                "1:000000:abc", "content-hash");

        assertThrows(VectorWriteStage.VectorWriteUnknownException.class,
                () -> stage.write(draft));
        assertEquals(1, milvus.rows.size(), "远端其实已经提交");
        assertEquals("FAILED", mysql.row.status());

        VectorWriteStage.Result replay = stage.write(draft);
        assertEquals(1, milvus.rows.size(), "upsert 覆盖同一确定性 ID");
        assertEquals("DONE", mysql.row.status());
        assertFalse(replay.replayed(), "它重做了调用，但逻辑结果仍只有一个");
    }

    // InMemoryChunks：按 versionId 与 businessKey 复用行的 Fake chunk 仓库。
    private static final class InMemoryChunks implements ChunkWriteRepository {
        private ChunkRow row;
        @Override public ChunkRow getOrCreate(ChunkDraft draft) {
            if (row == null) row = new ChunkRow(1L, draft.documentVersionId(),
                    draft.businessKey(), draft.contentHash(), "PENDING", null, null);
            if (!row.contentHash().equals(draft.contentHash()))
                throw new IllegalStateException("collision");
            return row;
        }
        @Override public void markDone(long id, long vectorId, String model) {
            row = new ChunkRow(id, row.documentVersionId(), row.businessKey(),
                    row.contentHash(), "DONE", vectorId, model);
        }
        @Override public void reserveVectorIdentity(
                long id, long vectorId, String model, int dimension) {
            // Fake 先固定本地向量身份，模拟生产唯一键在远端 upsert 前生效。
            if (row.vectorId() != null && row.vectorId() != vectorId) {
                throw new IllegalStateException("VECTOR_ID_COLLISION");
            }
            row = new ChunkRow(id, row.documentVersionId(), row.businessKey(),
                    row.contentHash(), row.status(), vectorId, model);
        }
        @Override public void markFailed(long id, String errorCode) {
            row = new ChunkRow(id, row.documentVersionId(), row.businessKey(),
                    row.contentHash(), "FAILED", row.vectorId(), row.embeddingModel());
        }
    }

    // LostFirstResponseVectors：先保存后抛错，模拟 Milvus 提交成功但响应丢失。
    private static final class LostFirstResponseVectors implements ConsistentVectorStore {
        private final Map<Long, VectorRecord> rows = new HashMap<>();
        private boolean loseResponse = true;
        @Override public void upsert(VectorRecord record) {
            rows.put(record.vectorId(), record);
            if (loseResponse) {
                loseResponse = false;
                throw new VectorWriteStage.VectorWriteUnknownException(
                        "response lost after commit");
            }
        }
        @Override
        public java.util.Optional<VectorMetadata> find(long id) {
            VectorRecord record = rows.get(id);
            if (record == null) return java.util.Optional.empty();
            return java.util.Optional.of(new VectorMetadata(
                    record.vectorId(), record.mysqlChunkId(),
                    record.documentVersionId(), record.chunkBusinessKey(),
                    record.contentHash(), record.embeddingModel(),
                    record.dimension()));
        }
        @Override public long countByVersion(long versionId) {
            return rows.values().stream()
                    .filter(v -> v.documentVersionId() == versionId).count();
        }
        @Override public Set<Long> listIdsByVersion(long versionId) {
            return rows.values().stream()
                    .filter(v -> v.documentVersionId() == versionId)
                    .map(VectorRecord::vectorId).collect(java.util.stream.Collectors.toSet());
        }
        @Override public void deleteByVersion(long versionId) {
            rows.values().removeIf(v -> v.documentVersionId() == versionId);
        }
    }
}