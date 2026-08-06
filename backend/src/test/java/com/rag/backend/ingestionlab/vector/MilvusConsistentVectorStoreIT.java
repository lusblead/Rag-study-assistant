package com.rag.backend.ingestionlab.vector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在显式启动的临时 Milvus 上验证 schema、upsert、清单分页接口和版本过滤。 */
@EnabledIfSystemProperty(named = "rag.milvus.it", matches = "true")
class MilvusConsistentVectorStoreIT {
    private static final int DIMENSION = 64;

    @Test
    void realMilvusKeepsIdentityAndFiltersInactiveVersions() throws Exception {
        String host = System.getProperty("rag.milvus.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty(
                "rag.milvus.port", "39530"));
        String collection = System.getProperty(
                "rag.milvus.collection", "scheme3_it_vectors_20260804");
        MilvusConsistentVectorStore store = new MilvusConsistentVectorStore(
                host, port, collection, DIMENSION, "AUTOINDEX");

        long activeVersion = 101L;
        long staleVersion = 99L;
        try {
            store.upsert(record(7001L, 1001L, activeVersion, axis(0)));
            store.upsert(record(7991L, 1991L, staleVersion, axis(0)));
            await(() -> store.find(7001L).isPresent(), Duration.ofSeconds(10));

            var metadata = store.find(7001L).orElseThrow();
            assertEquals(1001L, metadata.mysqlChunkId());
            assertEquals(activeVersion, metadata.documentVersionId());
            assertEquals(Set.of(7001L), store.listIdsByVersion(activeVersion));

            var hits = store.search(
                    10L, Set.of(activeVersion), axis(0), 10);
            assertEquals(1, hits.size());
            assertEquals(1001L, hits.getFirst().mysqlChunkId());
            assertEquals(activeVersion, hits.getFirst().documentVersionId());
        } finally {
            store.deleteByVersion(activeVersion);
            store.deleteByVersion(staleVersion);
            await(() -> store.countByVersion(activeVersion) == 0,
                    Duration.ofSeconds(10));
        }
    }

    private ConsistentVectorStore.VectorRecord record(
            long vectorId,
            long chunkId,
            long versionId,
            List<Double> embedding) {
        return new ConsistentVectorStore.VectorRecord(
                vectorId,
                chunkId,
                10L,
                20L,
                versionId,
                "business-" + chunkId,
                "a".repeat(64),
                "mock-it",
                DIMENSION,
                embedding);
    }

    private List<Double> axis(int oneAt) {
        List<Double> values = new ArrayList<>(DIMENSION);
        for (int index = 0; index < DIMENSION; index++) {
            values.add(index == oneAt ? 1.0 : 0.0);
        }
        return List.copyOf(values);
    }

    private void await(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "Milvus 未在期限内达到期望状态");
    }
}
