// 先做集合验证，再用短事务切换在线版本。
package com.rag.backend.ingestionlab.verify;

import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// 集合验证器：比较期望和实际向量，得到 missing 与 orphan。
public final class IndexVerifier {
    // 按业务键 get-or-create，重放不新增行。
    private final ChunkInventory chunks;
    // 支持指定 ID upsert，重复写才能收敛。
    private final ConsistentVectorStore vectors;

    // Verifier 同时读取 MySQL 期望清单和向量库实际清单，但不在内部修改任一存储。
    public IndexVerifier(ChunkInventory chunks, ConsistentVectorStore vectors) {
        this.chunks = chunks;
        this.vectors = vectors;
    }

    // 期望减实际得 missing，实际减期望得 orphan。
    public VerificationReport verify(long versionId, int expectedChunkCount) {
        int mysqlTotal = chunks.totalChunks(versionId);
        int mysqlDone = chunks.doneChunks(versionId);
        Set<Long> expectedIds = Set.copyOf(chunks.expectedVectorIds(versionId));
        Set<Long> actualIds = Set.copyOf(vectors.listIdsByVersion(versionId));

        Set<Long> missing = new TreeSet<>(expectedIds);
        missing.removeAll(actualIds);
        Set<Long> orphan = new TreeSet<>(actualIds);
        orphan.removeAll(expectedIds);

        boolean passed = mysqlTotal == expectedChunkCount
                && mysqlDone == expectedChunkCount
                && expectedIds.size() == expectedChunkCount
                && missing.isEmpty()
                && orphan.isEmpty();
        String digestMaterial = versionId + "|" + expectedChunkCount + "|"
                + mysqlTotal + "|" + mysqlDone + "|"
                + expectedIds.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(",")) + "|"
                + actualIds.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(","));
        return new VerificationReport(versionId, expectedChunkCount,
                mysqlTotal, mysqlDone, actualIds.size(), missing, orphan,
                passed, StableHash.sha256(digestMaterial));
    }

    // VerificationReport 保存数量、missing/orphan 集合、结论和 digest，VerificationService 据此推进 Version。
    public record VerificationReport(
            long versionId,
            int expectedChunkCount,
            int mysqlTotal,
            int mysqlDone,
            int vectorCount,
            Set<Long> missingVectorIds,
            Set<Long> orphanVectorIds,
            boolean passed,
            String digest) { }
}