package com.rag.backend.rerank;

import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.ChunkScores;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.rag.backend.agent.rerank.RerankExecutionResult.AttemptOutcome;
import static com.rag.backend.agent.rerank.RerankExecutionResult.FailureType;
import static com.rag.backend.agent.rerank.RerankExecutionResult.FallbackReason;
import static com.rag.backend.agent.rerank.RerankExecutionResult.Mode;
import static com.rag.backend.agent.rerank.RerankExecutionResult.SemanticEmptyReason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamicKnowledgeRerankerTest {

    @Test
    void noneKeepsOriginalCandidateOrder() {
        Fixture fixture = fixture("none", true, RerankPolicy.defaults());
        List<RetrievedChunk> input = List.of(
                chunk(20L, 0.8, "second-id-first"),
                chunk(10L, 0.7, "first-id-second"));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertEquals(List.of(20L, 10L), ids(result.chunks()));
        assertEquals(Mode.NONE, result.requestedReranker());
        assertEquals(Mode.NONE, result.actualReranker());
        assertEquals(AttemptOutcome.BYPASSED,
                result.attempts().getFirst().outcome());
        assertEquals(FallbackReason.NONE, result.fallbackReason());
        verifyNoInteractions(fixture.local());
        verify(fixture.settingsService(), never()).rerankRemote(
                anyString(), anyList(), anyInt(), any());
    }

    @Test
    void remoteSuccessUsesRemoteOrderAndExecutionMode() {
        Fixture fixture = fixture(
                "siliconflow", true, RerankPolicy.defaults());
        List<RetrievedChunk> input = candidates();
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        input.get(1).withRerankScore(0.95),
                        input.get(0).withRerankScore(0.25)));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertEquals(List.of(2L, 1L), ids(result.chunks()));
        assertEquals(Mode.REMOTE, result.requestedReranker());
        assertEquals(Mode.REMOTE, result.actualReranker());
        assertEquals(AttemptOutcome.SUCCESS,
                result.attempts().getFirst().outcome());
        assertEquals(FailureType.NONE,
                result.attempts().getFirst().failureType());
        assertFalse(result.degraded());
        verifyNoInteractions(fixture.local());
    }

    @Test
    void remoteTechnicalFailureFallsBackToLocal() {
        Fixture fixture = fixture(
                "siliconflow", true, RerankPolicy.defaults());
        List<RetrievedChunk> input = candidates();
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenThrow(technical(FailureType.HTTP_ERROR));
        when(fixture.local().rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(
                        input.get(1).withRerankScore(0.8),
                        input.get(0).withRerankScore(0.4)));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertEquals(List.of(2L, 1L), ids(result.chunks()));
        assertEquals(Mode.LOCAL, result.actualReranker());
        assertEquals(FallbackReason.REMOTE_TECHNICAL_FAILURE,
                result.fallbackReason());
        assertEquals(List.of(
                        AttemptOutcome.TECHNICAL_FAILURE,
                        AttemptOutcome.SUCCESS),
                result.attempts().stream()
                        .map(RerankExecutionResult.Attempt::outcome)
                        .toList());
        assertEquals(FailureType.HTTP_ERROR,
                result.attempts().getFirst().failureType());
        assertEquals(FailureType.NONE, result.terminalFailureType());
    }

    @Test
    void remoteAndLocalTechnicalFailuresFallBackToOriginal() {
        Fixture fixture = fixture(
                "siliconflow", true, RerankPolicy.defaults());
        List<RetrievedChunk> input = candidates();
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenThrow(technical(FailureType.NETWORK_ERROR));
        when(fixture.local().rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("local unavailable"));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertEquals(ids(input), ids(result.chunks()));
        assertEquals(Mode.ORIGINAL, result.actualReranker());
        assertEquals(FallbackReason.REMOTE_AND_LOCAL_TECHNICAL_FAILURE,
                result.fallbackReason());
        assertEquals(List.of(
                        FailureType.NETWORK_ERROR,
                        FailureType.EXECUTION_ERROR),
                result.attempts().stream()
                        .map(RerankExecutionResult.Attempt::failureType)
                        .toList());
        assertEquals(FailureType.NONE, result.terminalFailureType());
    }

    @Test
    void requestedLocalTechnicalFailureFallsBackToOriginal() {
        Fixture fixture = fixture(
                "local", true, RerankPolicy.defaults());
        List<RetrievedChunk> input = candidates();
        when(fixture.local().rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("local unavailable"));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertEquals(ids(input), ids(result.chunks()));
        assertEquals(Mode.LOCAL, result.requestedReranker());
        assertEquals(Mode.ORIGINAL, result.actualReranker());
        assertEquals(FallbackReason.LOCAL_TECHNICAL_FAILURE,
                result.fallbackReason());
        assertEquals(1, result.attempts().size());
        assertEquals(FailureType.EXECUTION_ERROR,
                result.attempts().getFirst().failureType());
    }

    @Test
    void failClosedCarriesSanitizedExecutionResult() {
        Fixture fixture = fixture(
                "siliconflow", false, RerankPolicy.defaults());
        List<RetrievedChunk> input = candidates();
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenThrow(technical(FailureType.TIMEOUT));

        RerankExecutionResult.ExecutionFailure failure = assertThrows(
                RerankExecutionResult.ExecutionFailure.class,
                () -> fixture.reranker()
                        .rerankWithResult("query", input, 2));

        RerankExecutionResult execution = failure.execution();
        assertTrue(execution.chunks().isEmpty());
        assertEquals(Mode.REMOTE, execution.requestedReranker());
        assertEquals(Mode.UNKNOWN, execution.actualReranker());
        assertEquals(FallbackReason.NONE, execution.fallbackReason());
        assertEquals(FailureType.TIMEOUT,
                execution.terminalFailureType());
        assertEquals(FailureType.TIMEOUT,
                execution.attempts().getFirst().failureType());
        assertEquals(input.size(), execution.inputCandidateCount());
        verifyNoInteractions(fixture.local());
    }

    @Test
    void requestedLocalFailureIsPropagatedWithExecutionWhenFailClosed() {
        Fixture fixture = fixture(
                "local", false, RerankPolicy.defaults());
        List<RetrievedChunk> input = candidates();
        when(fixture.local().rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("local unavailable"));

        RerankExecutionResult.ExecutionFailure failure = assertThrows(
                RerankExecutionResult.ExecutionFailure.class,
                () -> fixture.reranker()
                        .rerankWithResult("query", input, 2));

        assertEquals(Mode.LOCAL,
                failure.execution().requestedReranker());
        assertEquals(Mode.UNKNOWN,
                failure.execution().actualReranker());
        assertEquals(FailureType.EXECUTION_ERROR,
                failure.execution().terminalFailureType());
        assertEquals(1, failure.execution().attempts().size());
    }

    @Test
    void actualModeThresholdCanProduceSemanticEmptyWithoutFallback() {
        RerankPolicy policy = new RerankPolicy(
                new RerankPolicy.Threshold(false, 0.0),
                new RerankPolicy.Threshold(true, 0.9),
                disabledComposite());
        Fixture fixture = fixture("local", true, policy);
        List<RetrievedChunk> input = candidates();
        when(fixture.local().rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(
                        input.get(0).withRerankScore(0.4),
                        input.get(1).withRerankScore(0.3)));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertTrue(result.chunks().isEmpty());
        assertEquals(Mode.LOCAL, result.actualReranker());
        assertEquals(FallbackReason.NONE, result.fallbackReason());
        assertEquals(SemanticEmptyReason.ALL_BELOW_THRESHOLD,
                result.semanticEmptyReason());
        assertTrue(result.thresholdApplied());
        assertEquals(0.9, result.appliedThreshold());
        assertEquals(1, result.attempts().size());
        assertEquals(AttemptOutcome.SUCCESS,
                result.attempts().getFirst().outcome());
    }

    @Test
    void remoteSuccessSemanticEmptyDoesNotFallBackToLocal() {
        RerankPolicy policy = new RerankPolicy(
                new RerankPolicy.Threshold(true, 0.9),
                new RerankPolicy.Threshold(false, 0.0),
                disabledComposite());
        Fixture fixture = fixture("siliconflow", true, policy);
        List<RetrievedChunk> input = candidates();
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        input.get(0).withRerankScore(0.4),
                        input.get(1).withRerankScore(0.3)));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertTrue(result.chunks().isEmpty());
        assertEquals(Mode.REMOTE, result.actualReranker());
        assertEquals(SemanticEmptyReason.ALL_BELOW_THRESHOLD,
                result.semanticEmptyReason());
        assertEquals(FallbackReason.NONE, result.fallbackReason());
        verifyNoInteractions(fixture.local());
    }

    @Test
    void remoteFallbackUsesActualLocalThreshold() {
        RerankPolicy policy = new RerankPolicy(
                new RerankPolicy.Threshold(true, 0.95),
                new RerankPolicy.Threshold(true, 0.5),
                disabledComposite());
        Fixture fixture = fixture("siliconflow", true, policy);
        List<RetrievedChunk> input = candidates();
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenThrow(technical(FailureType.TIMEOUT));
        when(fixture.local().rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(
                        input.get(0).withRerankScore(0.6),
                        input.get(1).withRerankScore(0.4)));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", input, 2);

        assertEquals(Mode.LOCAL, result.actualReranker());
        assertEquals(0.5, result.appliedThreshold());
        assertEquals(List.of(1L), ids(result.chunks()));
        assertEquals(SemanticEmptyReason.NONE,
                result.semanticEmptyReason());
    }

    @Test
    void invalidRemoteCandidatePermutationIsTechnicalFailure() {
        Fixture fixture = fixture(
                "siliconflow", false, RerankPolicy.defaults());
        List<RetrievedChunk> input = candidates();
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of(input.get(0), input.get(0)));

        RerankExecutionResult.ExecutionFailure failure = assertThrows(
                RerankExecutionResult.ExecutionFailure.class,
                () -> fixture.reranker()
                        .rerankWithResult("query", input, 2));

        assertEquals(FailureType.INVALID_RESPONSE,
                failure.execution().terminalFailureType());
        assertEquals(FailureType.INVALID_RESPONSE,
                failure.execution().attempts().getFirst().failureType());
        assertEquals(AttemptOutcome.TECHNICAL_FAILURE,
                failure.execution().attempts().getFirst().outcome());
    }

    @Test
    void defaultCompositeIsDisabledAndPreservesStageScores() {
        Fixture fixture = fixture(
                "siliconflow", false, RerankPolicy.defaults());
        RetrievedChunk first = scoredChunk(
                1L, 0.8, 0.3, 0.6, "first");
        RetrievedChunk second = scoredChunk(
                2L, 0.2, 0.7, 0.4, "second");
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        first.withRerankScore(0.95),
                        second.withRerankScore(0.25)));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult("query", List.of(first, second), 2);

        RetrievedChunk rerankedFirst = byId(result.chunks(), 1L);
        assertFalse(result.compositeEnabled());
        assertNull(result.compositeVersion());
        assertEquals(0.8, rerankedFirst.scores().denseScore());
        assertEquals(0.3, rerankedFirst.scores().lexicalScore());
        assertEquals(0.6, rerankedFirst.scores().fusionScore());
        assertEquals(0.95, rerankedFirst.scores().rerankScore());
        assertEquals(0.95, rerankedFirst.scores().finalScore());
    }

    @Test
    void enabledCompositeChangesOnlyFinalScoreAndPreservesStageScores() {
        RerankPolicy policy = new RerankPolicy(
                new RerankPolicy.Threshold(false, 0.0),
                new RerankPolicy.Threshold(false, 0.0),
                new RerankPolicy.Composite(
                        true, "normalized-min-max-v1", 3.0, 1.0));
        Fixture fixture = fixture("siliconflow", false, policy);
        RetrievedChunk strongBase = scoredChunk(
                1L, 0.9, 0.2, 0.9, "strong-base");
        RetrievedChunk strongRerank = scoredChunk(
                2L, 0.1, 0.8, 0.1, "strong-rerank");
        when(fixture.settingsService().rerankRemote(
                anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        strongRerank.withRerankScore(0.9),
                        strongBase.withRerankScore(0.1)));

        RerankExecutionResult result = fixture.reranker()
                .rerankWithResult(
                        "query", List.of(strongBase, strongRerank), 2);

        RetrievedChunk first = byId(result.chunks(), 1L);
        RetrievedChunk second = byId(result.chunks(), 2L);
        assertTrue(result.compositeEnabled());
        assertEquals("normalized-min-max-v1", result.compositeVersion());
        assertEquals(List.of(1L, 2L), ids(result.chunks()));
        assertEquals(0.9, first.scores().denseScore());
        assertEquals(0.2, first.scores().lexicalScore());
        assertEquals(0.9, first.scores().fusionScore());
        assertEquals(0.1, first.scores().rerankScore());
        assertEquals(0.75, first.scores().finalScore(), 1.0e-12);
        assertEquals(0.1, second.scores().denseScore());
        assertEquals(0.8, second.scores().lexicalScore());
        assertEquals(0.1, second.scores().fusionScore());
        assertEquals(0.9, second.scores().rerankScore());
        assertEquals(0.25, second.scores().finalScore(), 1.0e-12);
    }

    private Fixture fixture(
            String provider,
            boolean failOpen,
            RerankPolicy policy) {
        RerankSettingsService settingsService =
                mock(RerankSettingsService.class);
        LocalLexicalKnowledgeReranker local =
                mock(LocalLexicalKnowledgeReranker.class);
        RerankSettings settings = new RerankSettings();
        settings.setProvider(provider);
        settings.setFailOpen(failOpen);
        when(settingsService.current()).thenReturn(settings);
        return new Fixture(
                new DynamicKnowledgeReranker(
                        settingsService, local, policy),
                settingsService,
                local);
    }

    private static RerankPolicy.Composite disabledComposite() {
        return new RerankPolicy.Composite(
                false, "normalized-min-max-v1", 0.5, 0.5);
    }

    private static RerankExecutionResult.TechnicalFailure technical(
            FailureType failureType) {
        return new RerankExecutionResult.TechnicalFailure(
                failureType, "test technical failure");
    }

    private static List<RetrievedChunk> candidates() {
        return List.of(
                chunk(1L, 0.8, "first content"),
                chunk(2L, 0.6, "second content"));
    }

    private static RetrievedChunk chunk(
            long chunkId,
            double score,
            String content) {
        return new RetrievedChunk(
                chunkId, 100L + chunkId, "document-" + chunkId,
                "title-" + chunkId, content, 1, score);
    }

    private static RetrievedChunk scoredChunk(
            long chunkId,
            double dense,
            double lexical,
            double fusion,
            String content) {
        ChunkScores scores = new ChunkScores(
                dense, lexical, fusion, null, fusion);
        return new RetrievedChunk(
                chunkId, 100L + chunkId, "document-" + chunkId,
                "title-" + chunkId, content, 1, fusion, scores);
    }

    private static List<Long> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::chunkId).toList();
    }

    private static RetrievedChunk byId(
            List<RetrievedChunk> chunks,
            long chunkId) {
        return chunks.stream()
                .filter(chunk -> chunk.chunkId() == chunkId)
                .findFirst()
                .orElseThrow();
    }

    private record Fixture(
            DynamicKnowledgeReranker reranker,
            RerankSettingsService settingsService,
            LocalLexicalKnowledgeReranker local) {
    }
}
