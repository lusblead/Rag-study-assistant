package com.rag.backend.agent.chat;

import com.rag.backend.agent.evidence.AnswerabilityDecision;
import com.rag.backend.agent.evidence.EvidenceDecisionPolicy;
import com.rag.backend.agent.evidence.EvidenceDecisionReason;
import com.rag.backend.agent.evidence.EvidenceDecisionRenderer;
import com.rag.backend.agent.evidence.EvidenceDecisionResult;
import com.rag.backend.agent.evidence.EvidenceObservedSignals;
import com.rag.backend.agent.grounding.CitationIntegrityValidator;
import com.rag.backend.agent.grounding.ClaimSupportEvaluator;
import com.rag.backend.agent.grounding.GroundedAnswerGenerator;
import com.rag.backend.agent.grounding.GroundingExecutionStatus;
import com.rag.backend.agent.grounding.GroundingFailureRenderer;
import com.rag.backend.agent.grounding.GroundingProperties;
import com.rag.backend.agent.grounding.GroundingRepairPromptTemplate;
import com.rag.backend.agent.grounding.GroundingValidator;
import com.rag.backend.agent.grounding.UncalibratedSemanticClaimJudge;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagChatStreamResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceGroundingTest {

    @Test
    void synchronousAnswerRepairsOnceAndReturnsFinalGroundingMetadata() {
        Fixture fixture = fixture();
        when(fixture.chatClient.call(anyString())).thenReturn(
                "本店支持30天无理由退货。[S1]",
                "本店支持退货。[S1]");

        RagChatResponse response = fixture.service.chat(
                1L, null, "是否支持退货？");

        assertEquals("本店支持退货。[S1]", response.answer());
        assertEquals(GroundingExecutionStatus.REPAIRED,
                response.metadata().grounding().status());
        assertEquals(2, response.metadata().grounding().generationAttempts());
        assertEquals(List.of("S1"),
                response.metadata().grounding().sourceIds());
        verify(fixture.history).appendMessage(
                77L, "assistant", "本店支持退货。[S1]");
    }

    @Test
    void strictSseBuffersBothCandidatesAndEmitsOnlyTheVerifiedAnswer() {
        Fixture fixture = fixture();
        when(fixture.chatClient.stream(anyString())).thenReturn(
                Flux.just("本店支持", "30天退货。[S1]"),
                Flux.just("本店支持退货。[S1]"));

        RagChatStreamResponse response = fixture.service.stream(
                1L, null, "是否支持退货？");

        assertEquals(GroundingExecutionStatus.REPAIRED,
                response.metadata().grounding().status());
        verify(fixture.history, never()).appendMessage(
                anyLong(), anyString(), anyString());
        assertEquals(List.of("本店支持退货。[S1]"),
                response.stream().collectList().block());
        verify(fixture.history).appendMessage(
                77L, "assistant", "本店支持退货。[S1]");
    }

    private Fixture fixture() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        @SuppressWarnings("unchecked")
        PromptTemplate<RagPromptContext> prompt = mock(PromptTemplate.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        EvidenceDecisionPolicy policy = mock(EvidenceDecisionPolicy.class);
        RetrievedChunk chunk = new RetrievedChunk(
                1L, 10L, "规则.md", "退货规则",
                "本店支持退货。", 1, 0.9);

        when(history.resolveSession(null, 1L, "是否支持退货？"))
                .thenReturn(77L);
        when(history.recentMessages(77L, 8)).thenReturn(List.of());
        when(retriever.retrieveWithResult(1L, "是否支持退货？", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(
                        List.of(chunk)));
        when(policy.decide(any())).thenReturn(answerDecision());
        when(prompt.render(any())).thenReturn("prompt-with-S1");

        RagChatService service = new RagChatService(
                retriever,
                prompt,
                chatClient,
                history,
                policy,
                new EvidenceDecisionRenderer(),
                enabledGenerator(),
                5,
                8);
        return new Fixture(chatClient, history, service);
    }

    private GroundedAnswerGenerator enabledGenerator() {
        GroundingProperties properties = new GroundingProperties();
        properties.setEnabled(true);
        return new GroundedAnswerGenerator(
                properties,
                new GroundingValidator(
                        new CitationIntegrityValidator(),
                        new ClaimSupportEvaluator(
                                new UncalibratedSemanticClaimJudge())),
                new GroundingRepairPromptTemplate(),
                new GroundingFailureRenderer());
    }

    private EvidenceDecisionResult answerDecision() {
        return new EvidenceDecisionResult(
                AnswerabilityDecision.ANSWER,
                EvidenceDecisionReason.DIRECT_SUPPORT_OBSERVED,
                List.of(1L),
                null,
                new EvidenceObservedSignals(
                        1, 1, 1, 1, 1.0, true,
                        false, false, false,
                        false, "LEGACY_FINAL", null, null,
                        false, RetrievalDiagnostics.EmptyReason.NONE),
                "test-policy-v1");
    }

    private record Fixture(
            ChatClient chatClient,
            ChatHistoryService history,
            RagChatService service
    ) {
    }
}
