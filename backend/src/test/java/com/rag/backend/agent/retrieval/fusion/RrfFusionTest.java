package com.rag.backend.agent.retrieval.fusion;

import com.rag.backend.agent.retrieval.CandidateBatch;
import com.rag.backend.agent.retrieval.CandidateSourceType;
import com.rag.backend.agent.retrieval.RetrievalCandidate;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    @Test
    void overlappingChunkSumsBothSourceContributionsExactly() {
        CandidateBatch dense = batch(CandidateSourceType.DENSE,
                candidate(7L, 0.91, 0));
        CandidateBatch lexical = batch(CandidateSourceType.LEXICAL,
                candidate(8L, 12.0, 0),
                candidate(7L, 10.0, 1));

        List<RetrievalCandidate> fused = new RrfFusion(60, 1.0, 2.0)
                .fuse(List.of(dense, lexical));

        double expected = 1.0 / 61.0 + 2.0 / 62.0;
        RetrievalCandidate overlapping = find(fused, 7L);
        double actual = overlapping.rawScore();
        assertEquals(2, fused.size(),
                "an overlapping chunk must appear only once in output");
        assertEquals(expected, actual, 1.0e-15);
        assertTrue(Math.abs(actual - 1.0 / 61.0) > 1.0e-12,
                "removing the lexical contribution must change the score");
        assertTrue(Math.abs(actual - 2.0 / 62.0) > 1.0e-12,
                "removing the dense contribution must change the score");
        assertEquals(0.91, overlapping.chunk().scores().denseScore());
        assertEquals(10.0, overlapping.chunk().scores().lexicalScore());
        assertEquals(expected,
                overlapping.chunk().scores().fusionScore(), 1.0e-15);
        assertEquals(expected,
                overlapping.chunk().scores().finalScore(), 1.0e-15);
        assertNull(overlapping.chunk().scores().rerankScore());
    }

    @Test
    void duplicateInsideOneSourceUsesOnlyItsFirstOneBasedRank() {
        CandidateBatch dense = batch(CandidateSourceType.DENSE,
                candidate(7L, 0.9, 0),
                candidate(7L, 0.8, 1),
                candidate(9L, 0.7, 2));
        CandidateBatch lexical = batch(CandidateSourceType.LEXICAL,
                candidate(10L, 5.0, 0));

        List<RetrievalCandidate> fused = new RrfFusion(60, 1.0, 1.0)
                .fuse(List.of(dense, lexical));

        assertEquals(1.0 / 61.0, find(fused, 7L).rawScore(), 1.0e-15);
        assertEquals(1.0 / 62.0, find(fused, 9L).rawScore(), 1.0e-15,
                "rank is recomputed after stable per-source deduplication");
    }

    @Test
    void zeroWeightSourceDoesNotAddCandidatesOrForceRrf() {
        CandidateBatch dense = batch(CandidateSourceType.DENSE,
                candidate(7L, 0.9, 0), candidate(9L, 0.7, 1));
        CandidateBatch lexical = batch(CandidateSourceType.LEXICAL,
                candidate(10L, 5.0, 0));

        List<RetrievalCandidate> result = new RrfFusion(60, 1.0, 0.0)
                .fuse(List.of(dense, lexical));

        assertEquals(List.of(7L, 9L), chunkIds(result));
        assertEquals(List.of(0.9, 0.7), rawScores(result));
    }

    @Test
    void singleNonEmptyZeroWeightSourcePreservesFailOpenRawOrder() {
        CandidateBatch dense = CandidateBatch.empty(
                CandidateSourceType.DENSE);
        CandidateBatch lexical = batch(CandidateSourceType.LEXICAL,
                candidate(10L, 5.0, 0),
                candidate(11L, 4.0, 1));

        List<RetrievalCandidate> result = new RrfFusion(
                60, 1.0, 0.0).fuse(List.of(dense, lexical));

        assertEquals(List.of(10L, 11L), chunkIds(result));
        assertEquals(List.of(5.0, 4.0), rawScores(result));
    }

    @Test
    void singleSuccessfulSourceFallsBackWithRawScoreOrderAndStableDeduplication() {
        CandidateBatch lexical = batch(CandidateSourceType.LEXICAL,
                candidate(9L, 8.0, 0),
                candidate(9L, 99.0, 1),
                candidate(4L, 3.0, 2));

        List<RetrievalCandidate> result = new RrfFusion(60, 1.0, 1.0)
                .fuse(List.of(lexical));

        assertEquals(List.of(9L, 4L), chunkIds(result));
        assertEquals(List.of(8.0, 3.0), rawScores(result));
        assertEquals(8.0, result.get(0).chunk().scores().lexicalScore());
        assertNull(result.get(0).chunk().scores().denseScore());
        assertNull(result.get(0).chunk().scores().fusionScore());
    }

    @Test
    void oneEmptySourceFallsBackToTheNonEmptySource() {
        CandidateBatch dense = CandidateBatch.empty(CandidateSourceType.DENSE);
        CandidateBatch lexical = batch(CandidateSourceType.LEXICAL,
                candidate(5L, 7.5, 0), candidate(2L, 6.5, 1));

        List<RetrievalCandidate> result = new RrfFusion(60, 1.0, 1.0)
                .fuse(List.of(dense, lexical));

        assertEquals(List.of(5L, 2L), chunkIds(result));
        assertEquals(List.of(7.5, 6.5), rawScores(result));
    }

    @Test
    void twoEmptySourcesReturnEmpty() {
        List<RetrievalCandidate> result = new RrfFusion(60, 1.0, 1.0)
                .fuse(List.of(
                        CandidateBatch.empty(CandidateSourceType.DENSE),
                        CandidateBatch.empty(CandidateSourceType.LEXICAL)));

        assertTrue(result.isEmpty());
    }

    @Test
    void equalScoresUseChunkIdAscendingAsTieBreaker() {
        CandidateBatch dense = batch(CandidateSourceType.DENSE,
                candidate(9L, 0.9, 0));
        CandidateBatch lexical = batch(CandidateSourceType.LEXICAL,
                candidate(3L, 9.0, 0));

        List<RetrievalCandidate> result = new RrfFusion(60, 1.0, 1.0)
                .fuse(List.of(dense, lexical));

        assertEquals(List.of(3L, 9L), chunkIds(result));
    }

    @Test
    void rejectsNonPositiveK() {
        assertThrows(IllegalArgumentException.class,
                () -> new RrfFusion(0, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RrfFusion(-1, 1.0, 1.0));
    }

    @Test
    void rejectsIllegalWeightsAndRequiresAtLeastOnePositiveSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new RrfFusion(60, Double.NaN, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RrfFusion(60, 1.0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new RrfFusion(60, -0.1, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RrfFusion(60, 0.0, 0.0));
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

    private static RetrievalCandidate find(
            List<RetrievalCandidate> candidates,
            long chunkId) {
        return candidates.stream()
                .filter(candidate -> candidate.chunk().chunkId() == chunkId)
                .findFirst()
                .orElseThrow();
    }

    private static List<Long> chunkIds(List<RetrievalCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.chunk().chunkId())
                .toList();
    }

    private static List<Double> rawScores(
            List<RetrievalCandidate> candidates) {
        return candidates.stream().map(RetrievalCandidate::rawScore).toList();
    }
}
