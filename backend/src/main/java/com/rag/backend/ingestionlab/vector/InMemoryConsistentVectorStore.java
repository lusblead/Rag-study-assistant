package com.rag.backend.ingestionlab.vector;

import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 本地/Mock 模式下的版本化向量端口。
 * 它保留与 Milvus Adapter 相同的身份和 activeVersionIds 过滤语义，但不构成真实 Milvus 证据。
 */
@Service
@ConditionalOnExpression("'${agent.mock:false}' == 'true' || '${vector.provider:milvus}' == 'local'")
public class InMemoryConsistentVectorStore
        implements ConsistentVectorStore, VersionedVectorSearch {
    private final Map<Long, StoredVector> vectors = new ConcurrentHashMap<>();

    @Override
    public void upsert(VectorRecord record) {
        vectors.put(record.vectorId(), new StoredVector(record));
    }

    @Override
    public void awaitVersionVisible(long documentVersionId,
                                    int expectedCount,
                                    Duration timeout) {
        if (expectedCount < 0 || timeout == null || timeout.isZero()
                || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Expected count must be non-negative and timeout positive");
        }
        // ConcurrentHashMap 写入立即可见；完整性差异由后续 IndexVerifier 判定。
    }

    @Override
    public Optional<VectorMetadata> find(long vectorId) {
        StoredVector stored = vectors.get(vectorId);
        return stored == null
                ? Optional.empty()
                : Optional.of(stored.metadata());
    }

    @Override
    public long countByVersion(long documentVersionId) {
        return vectors.values().stream()
                .filter(value -> value.record().documentVersionId() == documentVersionId)
                .count();
    }

    @Override
    public Set<Long> listIdsByVersion(long documentVersionId) {
        return vectors.values().stream()
                .map(StoredVector::record)
                .filter(value -> value.documentVersionId() == documentVersionId)
                .map(VectorRecord::vectorId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void deleteByVersion(long documentVersionId) {
        vectors.entrySet().removeIf(entry ->
                entry.getValue().record().documentVersionId() == documentVersionId);
    }

    @Override
    public List<VersionedVectorHit> search(
            long courseId,
            Set<Long> activeVersionIds,
            List<Double> queryVector,
            int topK) {
        if (activeVersionIds.isEmpty() || topK <= 0) {
            return List.of();
        }
        return vectors.values().stream()
                .map(StoredVector::record)
                .filter(value -> value.courseId() == courseId)
                .filter(value -> activeVersionIds.contains(value.documentVersionId()))
                .map(value -> new VersionedVectorHit(
                        value.mysqlChunkId(), value.documentVersionId(),
                        cosine(queryVector, value.embedding())))
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
    }

    private double cosine(List<Double> left, List<Double> right) {
        int size = Math.min(left.size(), right.size());
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < size; index++) {
            double a = left.get(index);
            double b = right.get(index);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record StoredVector(VectorRecord record) {
        VectorMetadata metadata() {
            return new VectorMetadata(
                    record.vectorId(), record.mysqlChunkId(),
                    record.documentVersionId(), record.chunkBusinessKey(),
                    record.contentHash(), record.embeddingModel(),
                    record.dimension());
        }
    }
}
