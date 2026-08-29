package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.rerank.NoOpKnowledgeReranker;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MilvusKnowledgeRetrieverDenseEquivalenceTest {

    @Test
    void candidateSourceRefactorPreservesDenseRetrievalOutput() {
        KnowledgeChunk first = chunk(11L, 101L, "first evidence");
        KnowledgeChunk second = chunk(12L, 101L, "second evidence");
        InMemoryRepository chunks = new InMemoryRepository(first, second);
        AtomicInteger scopeResolutions = new AtomicInteger();
        AtomicInteger requestedCandidateK = new AtomicInteger();
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                ignored -> List.of(1.0, 0.0),
                ignored -> {
                    scopeResolutions.incrementAndGet();
                    return Set.of(101L);
                },
                (courseId, activeIds, queryVector, topK) -> {
                    requestedCandidateK.set(topK);
                    return List.of(
                            new VersionedVectorHit(11L, 101L, 0.9),
                            new VersionedVectorHit(12L, 101L, 0.8));
                },
                chunks,
                null,
                new NoOpKnowledgeReranker(),
                0.2,
                20);

        List<RetrievedChunk> result = retriever.retrieve(7L, "query", 1);

        assertEquals(1, scopeResolutions.get(),
                "ACTIVE scope must be resolved exactly once per request");
        assertEquals(20, requestedCandidateK.get(),
                "source uses max(configured candidateK, final topK)");
        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).chunkId());
        assertEquals("first evidence", result.get(0).content());
        assertEquals(0.9, result.get(0).score());
        assertEquals(0, chunks.courseFallbackCalls);
    }

    private static KnowledgeChunk chunk(long id, long versionId, String content) {
        KnowledgeChunk value = new KnowledgeChunk();
        value.setId(id);
        value.setCourseId(7L);
        value.setDocumentId(3L);
        value.setDocumentVersionId(versionId);
        value.setTitle("doc");
        value.setContent(content);
        return value;
    }

    private static final class InMemoryRepository
            implements KnowledgeChunkRepository {
        private final List<KnowledgeChunk> values;
        private int courseFallbackCalls;

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

        @Override
        public List<KnowledgeChunk> findByCourseId(long courseId, int limit) {
            courseFallbackCalls++;
            return values;
        }

        @Override public void deleteByDocumentId(long documentId) { }
        @Override public void deleteByCourseId(long courseId) { }
        @Override public void updateVectorStatus(Long chunkId, String vectorId, String status) { }
    }
}
