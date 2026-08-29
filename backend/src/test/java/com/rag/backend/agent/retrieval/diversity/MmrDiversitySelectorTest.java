package com.rag.backend.agent.retrieval.diversity;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmrDiversitySelectorTest {

    @Test
    void disabledSelectorPreservesInputOrderAndLimitsFinalK() {
        List<RetrievedChunk> ranked = List.of(
                chunk(30L, 300L, "first evidence"),
                chunk(10L, 100L, "second evidence"),
                chunk(20L, 200L, "third evidence"));

        DiversitySelectionResult result = selector(false, 0.7)
                .select(ranked, 2);

        assertEquals(ranked.subList(0, 2), result.chunks(),
                "disabled MMR must preserve the exact upstream FinalK");
        assertFalse(result.diagnostics().enabled());
        assertEquals("disabled", result.diagnostics().strategy());
        assertEquals(3, result.diagnostics().inputCandidateCount());
        assertEquals(2, result.diagnostics().outputCandidateCount());
    }

    @Test
    void enabledSelectorUsesJaccardToAvoidNearDuplicateFinalK() {
        RetrievedChunk primary = chunk(
                1L, 101L, "retrieval evidence alpha beta");
        RetrievedChunk nearDuplicate = chunk(
                2L, 101L, "retrieval evidence alpha beta");
        RetrievedChunk complementary = chunk(
                3L, 303L, "database transaction isolation");
        List<RetrievedChunk> rerankedCandidateK = List.of(
                primary, nearDuplicate, complementary);

        DiversitySelectionResult result = selector(true, 0.5)
                .select(rerankedCandidateK, 2);

        assertEquals(List.of(1L, 3L), chunkIds(result.chunks()),
                "MMR must inspect beyond the upstream FinalK prefix");
        assertTrue(result.diagnostics().enabled());
        assertEquals(MmrDiversitySelector.STRATEGY,
                result.diagnostics().strategy());
        assertEquals(0.5, result.diagnostics().lambda());
        assertTrue(result.diagnostics().selectedMeanRedundancy()
                        < result.diagnostics().baselineMeanRedundancy(),
                "selected FinalK must be less redundant than the prefix");
        assertEquals(1,
                result.diagnostics().baselineUniqueDocumentCount());
        assertEquals(2,
                result.diagnostics().selectedUniqueDocumentCount());
    }

    @Test
    void lambdaBoundariesAreAccepted() {
        List<RetrievedChunk> ranked = List.of(
                chunk(40L, 400L, "alpha"),
                chunk(20L, 200L, "beta"),
                chunk(10L, 100L, "gamma"));

        MmrDiversitySelector diversityOnly = assertDoesNotThrow(
                () -> selector(true, 0.0));
        MmrDiversitySelector relevanceOnly = assertDoesNotThrow(
                () -> selector(true, 1.0));

        assertEquals(List.of(40L, 20L),
                chunkIds(diversityOnly.select(ranked, 2).chunks()));
        assertEquals(List.of(40L, 20L),
                chunkIds(relevanceOnly.select(ranked, 2).chunks()));
    }

    @Test
    void rejectsInvalidConfiguration() {
        for (double invalid : List.of(
                -0.01,
                1.01,
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class,
                    () -> selector(true, invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new MmrDiversitySelector(
                        true, 0.5, "cosine"));
        assertThrows(IllegalArgumentException.class,
                () -> new MmrDiversitySelector(
                        true, 0.5, null));
    }

    @Test
    void tiesRemainDeterministicByUpstreamRankAndChunkId() {
        List<RetrievedChunk> ranked = List.of(
                chunk(30L, 300L, "alpha"),
                chunk(10L, 100L, "beta"),
                chunk(20L, 200L, "gamma"));
        MmrDiversitySelector selector = selector(true, 0.0);

        for (int repetition = 0; repetition < 10; repetition++) {
            assertEquals(List.of(30L, 10L, 20L),
                    chunkIds(selector.select(ranked, 3).chunks()));
        }
    }

    @Test
    void outputRemainsCandidateSubsetAndFinalKBounded() {
        List<RetrievedChunk> candidates = List.of(
                chunk(1L, 101L, "alpha beta"),
                chunk(2L, 102L, "alpha beta"),
                chunk(3L, 103L, "gamma delta"),
                chunk(4L, 104L, "epsilon zeta"));
        MmrDiversitySelector selector = selector(true, 0.5);

        List<RetrievedChunk> bounded = selector.select(candidates, 2).chunks();
        Set<Long> candidateIds = Set.copyOf(chunkIds(candidates));
        assertEquals(2, bounded.size());
        assertTrue(candidateIds.containsAll(chunkIds(bounded)));

        List<RetrievedChunk> allAvailable = selector
                .select(candidates, 10)
                .chunks();
        assertEquals(candidates.size(), allAvailable.size(),
                "FinalK cannot expand the candidate pool");
        assertEquals(candidateIds, Set.copyOf(chunkIds(allAvailable)));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(candidates, 0));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(candidates, -1));
    }

    private static MmrDiversitySelector selector(
            boolean enabled,
            double lambda) {
        return new MmrDiversitySelector(
                enabled, lambda, MmrDiversitySelector.STRATEGY);
    }

    private static RetrievedChunk chunk(
            long chunkId,
            long documentId,
            String content) {
        return new RetrievedChunk(
                chunkId,
                documentId,
                "doc-" + documentId,
                content,
                1.0);
    }

    private static List<Long> chunkIds(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(RetrievedChunk::chunkId)
                .collect(Collectors.toList());
    }
}
