package com.rag.backend.agent.vector;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "agent.mock", havingValue = "true", matchIfMissing = true)
public class MockVectorStoreService implements VectorStoreService {
    private final Map<Long, StoredVector> vectors = new ConcurrentHashMap<>();

    @Override
    public String upsert(KnowledgeChunk chunk, List<Double> embedding) {
        vectors.put(chunk.getId(), new StoredVector(
                chunk.getId(),
                chunk.getCourseId(),
                chunk.getDocumentId(),
                embedding
        ));
        return String.valueOf(chunk.getId());
    }

    @Override
    public List<VectorSearchResult> search(Long courseId, List<Double> queryVector, int topK) {
        return vectors.values().stream()
                .filter(vector -> vector.courseId().equals(courseId))
                .map(vector -> new VectorSearchResult(vector.chunkId(), cosine(queryVector, vector.embedding())))
                .sorted(Comparator.comparing(VectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        List<Long> toDelete = new ArrayList<>();
        for (StoredVector vector : vectors.values()) {
            if (vector.documentId().equals(documentId)) {
                toDelete.add(vector.chunkId());
            }
        }
        toDelete.forEach(vectors::remove);
    }

    private double cosine(List<Double> a, List<Double> b) {
        int size = Math.min(a.size(), b.size());
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < size; i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record StoredVector(Long chunkId, Long courseId, Long documentId, List<Double> embedding) {}
}