package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.rerank.NoOpKnowledgeReranker;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.ingestionlab.retrieval.ActiveVersionResolver;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilvusKnowledgeRetrieverActiveVersionTest {

    @Test
    void emptyActiveVersionSetReturnsEmptyWithoutEmbeddingOrFallback() {
        CountingEmbedding embedding = new CountingEmbedding();
        CountingRepository chunks = new CountingRepository();
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                embedding,
                ignored -> Set.of(),
                (courseId, activeIds, query, topK) -> {
                    throw new AssertionError("vector search must not run");
                },
                chunks,
                null,
                new NoOpKnowledgeReranker(),
                -1.0,
                20);

        RetrievalExecutionResult result = retriever.retrieveWithResult(
                7L, "query", 5);

        assertTrue(result.chunks().isEmpty());
        assertEquals(RetrievalDiagnostics.EmptyReason.NO_ACTIVE_VERSION,
                result.diagnostics().emptyReason());
        assertTrue(result.diagnostics().sources().isEmpty());
        assertEquals(0, embedding.calls);
        assertEquals(0, chunks.courseFallbackCalls);
    }

    @Test
    void mysqlRecheckDropsCandidateFromAStaleVersion() {
        KnowledgeChunk active = chunk(11L, 101L, "active evidence");
        KnowledgeChunk stale = chunk(12L, 99L, "stale evidence");
        CountingRepository chunks = new CountingRepository(active, stale);
        ActiveVersionResolver activeVersions = ignored -> Set.of(101L);
        VersionedVectorSearch vectors = (courseId, activeIds, query, topK) -> List.of(
                new VersionedVectorHit(11L, 101L, 0.9),
                // 模拟远端元数据或查询时序错误；回表后必须再次拒绝。
                new VersionedVectorHit(12L, 101L, 0.8));
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                text -> List.of(1.0, 0.0),
                activeVersions,
                vectors,
                chunks,
                null,
                new NoOpKnowledgeReranker(),
                -1.0,
                20);

        List<RetrievedChunk> result = retriever.retrieve(7L, "query", 5);

        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).chunkId());
        assertEquals(0, chunks.courseFallbackCalls);
    }

    private KnowledgeChunk chunk(long id, long versionId, String content) {
        KnowledgeChunk value = new KnowledgeChunk();
        value.setId(id);
        value.setCourseId(7L);
        value.setDocumentId(3L);
        value.setDocumentVersionId(versionId);
        value.setTitle("doc");
        value.setContent(content);
        return value;
    }

    private static final class CountingEmbedding implements EmbeddingClient {
        private int calls;

        @Override
        public List<Double> embed(String text) {
            calls++;
            return List.of(1.0, 0.0);
        }
    }

    private static final class CountingRepository
            implements KnowledgeChunkRepository {
        private final List<KnowledgeChunk> values;
        private int courseFallbackCalls;

        private CountingRepository(KnowledgeChunk... values) {
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
        @Override public void updateVectorStatus(Long id, String vectorId, String status) { }
    }
}
