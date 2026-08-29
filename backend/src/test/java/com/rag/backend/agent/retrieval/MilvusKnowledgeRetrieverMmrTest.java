package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.retrieval.diversity.DiversitySelector;
import com.rag.backend.agent.retrieval.diversity.MmrDiversitySelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilvusKnowledgeRetrieverMmrTest {

    @Test
    void disabledMmrPreservesCurrentFinalKAndRerankLimit() {
        RecordingDenseSource dense = new RecordingDenseSource(
                rankedSourceCandidates());
        RecordingReranker reranker = new RecordingReranker();
        MilvusKnowledgeRetriever retriever = retriever(
                dense, reranker, mmr(false));

        RetrievalExecutionResult execution = retriever.retrieveWithResult(
                7L, "query", 2);

        assertEquals(2, reranker.requestedTopK,
                "disabled MMR must keep passing FinalK to the reranker");
        assertEquals(List.of(1L, 2L, 3L), chunkIds(reranker.received),
                "the reranker input remains the frozen CandidateK pool");
        assertEquals(List.of(1L, 2L), chunkIds(execution.chunks()),
                "disabled MMR must preserve the reranker's FinalK prefix");
        assertFalse(execution.diagnostics().diversity().enabled());
        assertEquals(4, dense.requestedSourceK);
    }

    @Test
    void enabledMmrReceivesOnlyTheFrozenCandidateKPool() {
        RecordingDenseSource dense = new RecordingDenseSource(
                rankedSourceCandidates());
        RecordingReranker reranker = new RecordingReranker();
        MilvusKnowledgeRetriever retriever = retriever(
                dense, reranker, mmr(true));

        RetrievalExecutionResult execution = retriever.retrieveWithResult(
                7L, "query", 2);

        assertEquals(3, reranker.requestedTopK,
                "enabled MMR needs the complete frozen CandidateK rerank");
        assertEquals(List.of(1L, 2L, 3L), chunkIds(reranker.received),
                "candidates beyond CandidateK must not enter rerank");
        assertEquals(List.of(1L, 3L), chunkIds(execution.chunks()),
                "MMR must select FinalK after complete CandidateK rerank");
        assertFalse(chunkIds(execution.chunks()).contains(4L));
        assertEquals(2, execution.chunks().size());
        assertTrue(execution.diagnostics().diversity().enabled());
        assertTrue(execution.diagnostics().diversity()
                .selectedMeanRedundancy()
                < execution.diagnostics().diversity()
                .baselineMeanRedundancy());
    }

    @Test
    void emptyCandidatePoolRemainsEmptyForBothMmrBranches() {
        for (boolean enabled : List.of(false, true)) {
            RecordingDenseSource dense = new RecordingDenseSource(List.of());
            RecordingReranker reranker = new RecordingReranker();
            MilvusKnowledgeRetriever retriever = retriever(
                    dense, reranker, mmr(enabled));

            RetrievalExecutionResult execution = retriever.retrieveWithResult(
                    7L, "query", 2);

            assertEquals(1, reranker.calls);
            assertEquals(2, reranker.requestedTopK,
                    "an empty pool must not request a synthetic CandidateK");
            assertTrue(reranker.received.isEmpty());
            assertTrue(execution.chunks().isEmpty());
            assertEquals(RetrievalDiagnostics.EmptyReason.NO_SOURCE_CANDIDATE,
                    execution.diagnostics().emptyReason());
            assertEquals(0, execution.diagnostics().diversity()
                    .inputCandidateCount());
            assertEquals(0, execution.diagnostics().diversity()
                    .outputCandidateCount());
        }
    }

    private static MilvusKnowledgeRetriever retriever(
            CandidateSource dense,
            KnowledgeReranker reranker,
            DiversitySelector diversitySelector) {
        return new MilvusKnowledgeRetriever(
                ignored -> Set.of(101L),
                dense,
                null,
                reranker,
                4,
                4,
                3,
                false,
                60,
                1.0,
                1.0,
                diversitySelector);
    }

    private static MmrDiversitySelector mmr(boolean enabled) {
        return new MmrDiversitySelector(
                enabled, 0.5, MmrDiversitySelector.STRATEGY);
    }

    private static List<RetrievalCandidate> rankedSourceCandidates() {
        return List.of(
                candidate(1L, 101L,
                        "retrieval evidence alpha beta", 0.9, 0),
                candidate(2L, 101L,
                        "retrieval evidence alpha beta", 0.8, 1),
                candidate(3L, 303L,
                        "database transaction isolation", 0.7, 2),
                candidate(4L, 404L,
                        "compiler parser syntax", 0.6, 3));
    }

    private static RetrievalCandidate candidate(
            long chunkId,
            long documentId,
            String content,
            double rawScore,
            long stableOrder) {
        RetrievedChunk chunk = new RetrievedChunk(
                chunkId,
                documentId,
                "doc-" + documentId,
                content,
                rawScore);
        return new RetrievalCandidate(chunk, rawScore, stableOrder);
    }

    private static List<Long> chunkIds(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::chunkId).toList();
    }

    private static final class RecordingDenseSource
            implements CandidateSource {
        private final CandidateBatch batch;
        private int requestedSourceK;

        private RecordingDenseSource(List<RetrievalCandidate> candidates) {
            this.batch = new CandidateBatch(
                    CandidateSourceType.DENSE, candidates);
        }

        @Override
        public CandidateSourceType type() {
            return CandidateSourceType.DENSE;
        }

        @Override
        public CandidateBatch retrieve(
                RetrievalScope scope,
                String query,
                int candidateK) {
            requestedSourceK = candidateK;
            return batch;
        }
    }

    private static final class RecordingReranker
            implements KnowledgeReranker {
        private int calls;
        private int requestedTopK;
        private List<RetrievedChunk> received = new ArrayList<>();

        @Override
        public List<RetrievedChunk> rerank(
                String query,
                List<RetrievedChunk> chunks,
                int topK) {
            calls++;
            requestedTopK = topK;
            received = List.copyOf(chunks);
            return chunks.stream().limit(topK).toList();
        }
    }
}
