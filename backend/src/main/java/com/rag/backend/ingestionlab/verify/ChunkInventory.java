// 先做集合验证，再用短事务切换在线版本。
package com.rag.backend.ingestionlab.verify;

import java.util.Set;

// ChunkInventory 从 MySQL 提供某个 Version 的总行数、DONE 数和期望 vectorId 集合，供 Verifier 比较。
public interface ChunkInventory {
    int totalChunks(long versionId);
    int doneChunks(long versionId);
    Set<Long> expectedVectorIds(long versionId);
}