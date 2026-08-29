package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DenseCandidateSourceTest {

    @Test
    void returnsRawScoresAndStableOrderWithinFrozenScope() {
        InMemoryRepository chunks = new InMemoryRepository(
                chunk(11L, 101L, "first"),
                chunk(12L, 101L, "below threshold"),
                chunk(13L, 99L, "stale"),
                chunk(14L, 101L, "last"));
        RecordingSearch search = new RecordingSearch(List.of(
                new VersionedVectorHit(11L, 101L, 0.9),
                new VersionedVectorHit(12L, 101L, 0.1),
                new VersionedVectorHit(13L, 101L, 0.85),
                new VersionedVectorHit(14L, 101L, 0.8)));
        DenseCandidateSource source = new DenseCandidateSource(
                ignored -> List.of(1.0, 0.0),
                search,
                chunks,
                null,
                0.2,
                true);
        RetrievalScope scope = new RetrievalScope(7L, Set.of(101L));

        CandidateBatch batch = source.retrieve(scope, "query", 3);

        assertEquals(CandidateSourceType.DENSE, batch.source());
        assertEquals(List.of(11L, 14L), batch.candidates().stream()
                .map(candidate -> candidate.chunk().chunkId())
                .toList());
        assertEquals(List.of(0.9, 0.8), batch.candidates().stream()
                .map(RetrievalCandidate::rawScore)
                .toList());
        assertEquals(List.of(0L, 3L), batch.candidates().stream()
                .map(RetrievalCandidate::stableOrder)
                .toList());
        assertEquals(7L, search.courseId);
        assertEquals(Set.of(101L), search.activeVersionIds);
        assertEquals(3, search.topK);
    }

    @Test
    void retrievalScopeDefensivelyCopiesActiveVersions() {
        java.util.HashSet<Long> versions = new java.util.HashSet<>(Set.of(101L));
        RetrievalScope scope = new RetrievalScope(7L, versions);

        versions.add(202L);

        assertEquals(Set.of(101L), scope.activeVersionIds());
        assertThrows(UnsupportedOperationException.class,
                () -> scope.activeVersionIds().add(303L));
    }

    private static KnowledgeChunk chunk(long id, long versionId, String content) {
        KnowledgeChunk value = new KnowledgeChunk();
        value.setId(id);
        value.setCourseId(7L);
        value.setDocumentId(id + 100L);
        value.setDocumentVersionId(versionId);
        value.setTitle("doc-" + id);
        value.setContent(content);
        return value;
    }

    private static final class RecordingSearch implements VersionedVectorSearch {
        private final List<VersionedVectorHit> hits;
        private long courseId;
        private Set<Long> activeVersionIds;
        private int topK;

        private RecordingSearch(List<VersionedVectorHit> hits) {
            this.hits = hits;
        }

        @Override
        public List<VersionedVectorHit> search(
                long courseId,
                Set<Long> activeVersionIds,
                List<Double> queryVector,
                int topK) {
            this.courseId = courseId;
            this.activeVersionIds = Set.copyOf(activeVersionIds);
            this.topK = topK;
            return hits;
        }
    }

    private static final class InMemoryRepository
            implements KnowledgeChunkRepository {
        private final List<KnowledgeChunk> values;

        private InMemoryRepository(KnowledgeChunk... values) {
            this.values = List.of(values);
        }

        @Override public KnowledgeChunk save(KnowledgeChunk chunk) { return chunk; }

        @Override
        public KnowledgeChunk findById(long id) {
            return values.stream()
                    .filter(value -> value.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override public List<KnowledgeChunk> findByCourseId(long courseId, int limit) { return List.of(); }
        @Override public void deleteByDocumentId(long documentId) { }
        @Override public void deleteByCourseId(long courseId) { }
        @Override public void updateVectorStatus(Long chunkId, String vectorId, String status) { }
    }
}
