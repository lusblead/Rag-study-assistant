package com.rag.backend.ingestionlab.verify;

import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * ChunkInventory 的 MyBatis 适配器。
 *
 * 调用关系：
 * IndexVerifier → ChunkInventory（端口）→ MyBatisChunkInventory → ChunkInventoryMapper → MySQL。
 */
@Repository
public class MyBatisChunkInventory implements ChunkInventory {
    private final ChunkInventoryMapper mapper;

    public MyBatisChunkInventory(ChunkInventoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int totalChunks(long versionId) {
        return mapper.countAll(versionId);
    }

    @Override
    public int doneChunks(long versionId) {
        return mapper.countDone(versionId);
    }

    @Override
    public Set<Long> expectedVectorIds(long versionId) {
        Set<Long> ids = mapper.findExpectedVectorIds(versionId);
        // 某些 MyBatis/驱动组合在没有结果时可能返回空集合；
        // 这里同时防御自定义 Fake 返回 null，避免 Verifier 出现无意义空指针。
        return ids == null ? Set.of() : Set.copyOf(ids);
    }
}