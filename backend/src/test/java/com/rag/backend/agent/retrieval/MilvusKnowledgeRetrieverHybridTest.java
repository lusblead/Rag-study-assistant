package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.rerank.KnowledgeReranker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MilvusKnowledgeRetrieverHybridTest {

    @Test
    void disabledGateKeepsDenseOnlyBehaviorAndNeverCallsLexical() {
        RecordingSource dense = returning(CandidateSourceType.DENSE,
                batch(CandidateSourceType.DENSE,
                        candidate(4L, 0.84, 0)));
        RecordingSource lexical = returning(CandidateSourceType.LEXICAL,
                batch(CandidateSourceType.LEXICAL,
                        candidate(9L, 9.0, 0)));
        RecordingReranker reranker = new RecordingReranker();
        MilvusKnowledgeRetriever retriever = retriever(
                dense, lexical, reranker, false, 1.0, 1.0,
                new AtomicInteger());

        List<RetrievedChunk> result = retriever.retrieve(7L, "query", 5);

        assertEquals(1, dense.calls);
        assertEquals(0, lexical.calls);
        assertEquals(List.of(4L), chunkIds(result));
        assertEquals(0.84, result.get(0).score());
        assertEquals(List.of(4L), chunkIds(reranker.received));
    }

    @Test
    void enabledGateUsesConfiguredWeightsOneScopeRerankAndFinalTopK() {
        RecordingSource dense = returning(CandidateSourceType.DENSE,
                batch(CandidateSourceType.DENSE,
                        candidate(4L, 0.99, 0)));
        RecordingSource lexical = returning(CandidateSourceType.LEXICAL,
                batch(CandidateSourceType.LEXICAL,
                        candidate(9L, 12.0, 0)));
        RecordingReranker reranker = new RecordingReranker();
        AtomicInteger scopeResolutions = new AtomicInteger();
        MilvusKnowledgeRetriever retriever = retriever(
                dense, lexical, reranker, true, 1.0, 3.0,
                scopeResolutions);

        List<RetrievedChunk> result = retriever.retrieve(7L, "query", 1);

        assertEquals(1, scopeResolutions.get());
        assertEquals(1, dense.calls);
        assertEquals(1, lexical.calls);
        assertSame(dense.lastScope, lexical.lastScope,
                "both sources must receive the exact same immutable scope");
        assertEquals(List.of(9L, 4L), chunkIds(reranker.received),
                "configured lexical weight must affect fused order before rerank");
        assertEquals(3.0 / 61.0, reranker.received.get(0).score(), 1.0e-15);
        assertEquals(1.0 / 61.0, reranker.received.get(1).score(), 1.0e-15);
        assertEquals(List.of(9L), chunkIds(result),
                "the retriever must apply final topK after rerank");
    }

    @Test
    void oneSourceFailureFailsOpenWithSuccessfulSourceRawOrderAndScores() {
        RecordingSource dense = failing(
                CandidateSourceType.DENSE,
                CandidateSourceFailureType.TIMEOUT);
        RecordingSource lexical = returning(CandidateSourceType.LEXICAL,
                batch(CandidateSourceType.LEXICAL,
                        candidate(8L, 7.0, 0),
                        candidate(3L, 5.0, 1)));
        RecordingReranker reranker = new RecordingReranker();
        MilvusKnowledgeRetriever retriever = retriever(
                dense, lexical, reranker, true, 1.0, 1.0,
                new AtomicInteger());

        RetrievalExecutionResult execution = retriever.retrieveWithResult(
                7L, "query", 2);
        List<RetrievedChunk> result = execution.chunks();

        assertEquals(List.of(8L, 3L), chunkIds(reranker.received));
        assertEquals(List.of(7.0, 5.0), scores(reranker.received));
        assertEquals(List.of(8L, 3L), chunkIds(result));
        assertSame(dense.lastScope, lexical.lastScope);
        assertEquals(true, execution.diagnostics().degraded());
        assertEquals(CandidateSourceFailureType.TIMEOUT,
                execution.diagnostics().sources().get(0).failureType());
        assertEquals(RetrievalDiagnostics.EmptyReason.NONE,
                execution.diagnostics().emptyReason());
    }

    @Test
    void successfulEmptySourcesExposeSemanticEmptyWithoutTechnicalFailure() {
        RecordingSource dense = returning(
                CandidateSourceType.DENSE,
                CandidateBatch.empty(CandidateSourceType.DENSE));
        RecordingSource lexical = returning(
                CandidateSourceType.LEXICAL,
                CandidateBatch.empty(CandidateSourceType.LEXICAL));
        MilvusKnowledgeRetriever retriever = retriever(
                dense, lexical, new RecordingReranker(), true,
                1.0, 1.0, new AtomicInteger());

        RetrievalExecutionResult execution = retriever.retrieveWithResult(
                7L, "query", 2);

        assertEquals(List.of(), execution.chunks());
        assertEquals(RetrievalDiagnostics.EmptyReason.NO_SOURCE_CANDIDATE,
                execution.diagnostics().emptyReason());
        assertEquals(false, execution.diagnostics().degraded());
        assertEquals(2, execution.diagnostics().sources().size());
    }

    @Test
    void bothSourceFailuresPropagateCandidateCollectionException() {
        RecordingSource dense = failing(
                CandidateSourceType.DENSE,
                CandidateSourceFailureType.SOURCE_ERROR);
        RecordingSource lexical = failing(
                CandidateSourceType.LEXICAL,
                CandidateSourceFailureType.INVALID_BATCH);
        MilvusKnowledgeRetriever retriever = retriever(
                dense, lexical, new RecordingReranker(), true,
                1.0, 1.0, new AtomicInteger());

        CandidateCollectionException failure = assertThrows(
                CandidateCollectionException.class,
                () -> retriever.retrieve(7L, "query", 2));

        assertEquals(2, failure.diagnostics().size());
    }

    @Test
    void globalCandidateKCapsFusedUnionBeforeRerank() {
        RecordingSource dense = returning(CandidateSourceType.DENSE,
                batch(CandidateSourceType.DENSE,
                        candidate(1L, 0.9, 0),
                        candidate(2L, 0.8, 1),
                        candidate(3L, 0.7, 2),
                        candidate(4L, 0.6, 3)));
        RecordingSource lexical = returning(CandidateSourceType.LEXICAL,
                batch(CandidateSourceType.LEXICAL,
                        candidate(5L, 9.0, 0),
                        candidate(6L, 8.0, 1),
                        candidate(7L, 7.0, 2),
                        candidate(8L, 6.0, 3)));
        RecordingReranker reranker = new RecordingReranker();
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                ignored -> Set.of(101L),
                dense,
                lexical,
                reranker,
                4,
                5,
                3,
                true,
                60,
                1.0,
                1.0);

        List<RetrievedChunk> result = retriever.retrieve(
                7L, "query", 2);

        assertEquals(4, dense.lastCandidateK);
        assertEquals(5, lexical.lastCandidateK);
        assertEquals(3, reranker.received.size(),
                "only the global CandidateK set may enter rerank");
        assertEquals(2, result.size(),
                "FinalK is applied only after rerank");
    }

    @Test
    void blankQueryIsRejectedBeforeHybridSources() {
        RecordingSource dense = returning(
                CandidateSourceType.DENSE,
                CandidateBatch.empty(CandidateSourceType.DENSE));
        RecordingSource lexical = returning(
                CandidateSourceType.LEXICAL,
                CandidateBatch.empty(CandidateSourceType.LEXICAL));
        MilvusKnowledgeRetriever retriever = retriever(
                dense, lexical, new RecordingReranker(), true,
                1.0, 1.0, new AtomicInteger());

        assertThrows(IllegalArgumentException.class,
                () -> retriever.retrieve(7L, "   ", 2));

        assertEquals(0, dense.calls);
        assertEquals(0, lexical.calls);
    }

    private static MilvusKnowledgeRetriever retriever(
            CandidateSource dense,
            CandidateSource lexical,
            KnowledgeReranker reranker,
            boolean enabled,
            double denseWeight,
            double lexicalWeight,
            AtomicInteger scopeResolutions) {
        return new MilvusKnowledgeRetriever(
                ignored -> {
                    scopeResolutions.incrementAndGet();
                    return Set.of(101L, 102L);
                },
                dense,
                lexical,
                reranker,
                20,
                enabled,
                60,
                denseWeight,
                lexicalWeight);
    }

    private static CandidateBatch batch(
            CandidateSourceType source,
            RetrievalCandidate... candidates) {
        return new CandidateBatch(source, List.of(candidates));
    }

    private static RetrievalCandidate candidate(
            long chunkId,
            double rawScore,
            long stableOrder) {
        RetrievedChunk chunk = new RetrievedChunk(
                chunkId, 100L + chunkId, "doc-" + chunkId,
                "content-" + chunkId, rawScore);
        return new RetrievalCandidate(chunk, rawScore, stableOrder);
    }

    private static RecordingSource returning(
            CandidateSourceType type,
            CandidateBatch batch) {
        return new RecordingSource(type, batch, null);
    }

    private static RecordingSource failing(
            CandidateSourceType type,
            CandidateSourceFailureType failureType) {
        return new RecordingSource(type, null, failureType);
    }

    private static List<Long> chunkIds(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::chunkId).toList();
    }

    private static List<Double> scores(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::score).toList();
    }

    private static final class RecordingSource implements CandidateSource {
        private final CandidateSourceType type;
        private final CandidateBatch batch;
        private final CandidateSourceFailureType failureType;
        private int calls;
        private int lastCandidateK;
        private RetrievalScope lastScope;

        private RecordingSource(
                CandidateSourceType type,
                CandidateBatch batch,
                CandidateSourceFailureType failureType) {
            this.type = type;
            this.batch = batch;
            this.failureType = failureType;
        }

        @Override
        public CandidateSourceType type() {
            return type;
        }

        @Override
        public CandidateBatch retrieve(
                RetrievalScope scope,
                String query,
                int candidateK) {
            calls++;
            lastCandidateK = candidateK;
            lastScope = scope;
            if (failureType != null) {
                throw new CandidateSourceException(
                        failureType, "isolated source failure");
            }
            return batch;
        }
    }

    private static final class RecordingReranker
            implements KnowledgeReranker {
        private List<RetrievedChunk> received = new ArrayList<>();

        @Override
        public List<RetrievedChunk> rerank(
                String query,
                List<RetrievedChunk> chunks,
                int topK) {
            received = List.copyOf(chunks);
            return chunks;
        }
    }
}
