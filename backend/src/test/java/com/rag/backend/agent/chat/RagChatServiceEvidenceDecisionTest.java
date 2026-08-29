package com.rag.backend.agent.chat;

import com.rag.backend.agent.evidence.AnswerabilityDecision;
import com.rag.backend.agent.evidence.EvidenceDecisionPolicy;
import com.rag.backend.agent.evidence.EvidenceDecisionReason;
import com.rag.backend.agent.evidence.EvidenceDecisionRenderer;
import com.rag.backend.agent.evidence.EvidenceDecisionResult;
import com.rag.backend.agent.evidence.EvidenceObservedSignals;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceEvidenceDecisionTest {

    @Test
    void clarifySkipsPromptAndModelButPersistsTheCompletedLocalTurn() {
        Fixture fixture = fixture();
        RetrievedChunk chunk = chunk();
        when(fixture.history.resolveSession(null, 1L, "这个是什么？"))
                .thenReturn(41L);
        when(fixture.history.recentMessages(41L, 8)).thenReturn(List.of());
        when(fixture.retriever.retrieveWithResult(
                1L, "这个是什么？", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(
                        List.of(chunk)));
        when(fixture.policy.decide(any())).thenReturn(clarify());

        RagChatResponse response = fixture.service.chat(
                1L, null, "这个是什么？");

        assertEquals("为了避免猜测，请说明你所指的对象。", response.answer());
        assertEquals(List.of(), response.references());
        verify(fixture.prompt, never()).render(any());
        verify(fixture.chatClient, never()).call(anyString());
        verify(fixture.chatClient, never()).stream(anyString());
        verify(fixture.history).appendMessage(41L, "user", "这个是什么？");
        verify(fixture.history).appendMessage(
                41L, "assistant", "为了避免猜测，请说明你所指的对象。");
    }

    @Test
    void retrievalTechnicalFailureIsNotConvertedIntoSemanticRefusal() {
        Fixture fixture = fixture();
        IllegalStateException technicalFailure = new IllegalStateException(
                "retrieval unavailable");
        when(fixture.history.resolveSession(null, 1L, "清晰问题"))
                .thenReturn(42L);
        when(fixture.history.recentMessages(42L, 8)).thenReturn(List.of());
        when(fixture.retriever.retrieveWithResult(1L, "清晰问题", 5))
                .thenThrow(technicalFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> fixture.service.chat(1L, null, "清晰问题"));

        assertSame(technicalFailure, thrown);
        verify(fixture.policy, never()).decide(any());
        verify(fixture.chatClient, never()).call(anyString());
        verify(fixture.history, never()).appendMessage(
                anyLong(), anyString(), anyString());
    }

    @Test
    void responseExposesRequestedActualAndFallbackWithoutDuplicatingChunks() {
        Fixture fixture = fixture();
        RetrievedChunk chunk = chunk();
        RetrievalDiagnostics diagnostics = degradedDiagnostics();
        when(fixture.history.resolveSession(null, 1L, "默认回滚规则是什么？"))
                .thenReturn(43L);
        when(fixture.history.recentMessages(43L, 8)).thenReturn(List.of());
        when(fixture.retriever.retrieveWithResult(
                1L, "默认回滚规则是什么？", 5))
                .thenReturn(new RetrievalExecutionResult(
                        List.of(chunk), diagnostics));
        when(fixture.policy.decide(any())).thenReturn(answer());
        when(fixture.prompt.render(any())).thenReturn("prompt");
        when(fixture.chatClient.call("prompt")).thenReturn("answer");

        RagChatResponse response = fixture.service.chat(
                1L, null, "默认回滚规则是什么？");

        assertEquals(RerankExecutionResult.Mode.REMOTE,
                response.metadata().retrieval().rerank().requestedReranker());
        assertEquals(RerankExecutionResult.Mode.LOCAL,
                response.metadata().retrieval().rerank().actualReranker());
        assertEquals(RerankExecutionResult.FallbackReason
                        .REMOTE_TECHNICAL_FAILURE,
                response.metadata().retrieval().rerank().fallbackReason());
        assertEquals(List.of(chunk), response.references());
    }

    @Test
    void answerWithUnknownEvidenceIdFailsClosedBeforeSynchronousModelCall() {
        Fixture fixture = fixture();
        when(fixture.history.resolveSession(null, 1L, "默认回滚规则是什么？"))
                .thenReturn(44L);
        when(fixture.history.recentMessages(44L, 8)).thenReturn(List.of());
        when(fixture.retriever.retrieveWithResult(
                1L, "默认回滚规则是什么？", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(
                        List.of(chunk())));
        when(fixture.policy.decide(any())).thenReturn(answer(999L));

        assertThrows(IllegalStateException.class,
                () -> fixture.service.chat(
                        1L, null, "默认回滚规则是什么？"));

        verify(fixture.prompt, never()).render(any());
        verify(fixture.chatClient, never()).call(anyString());
        verify(fixture.history, never()).appendMessage(
                anyLong(), anyString(), anyString());
    }

    @Test
    void answerWithUnknownEvidenceIdFailsClosedBeforeStreamingModelCall() {
        Fixture fixture = fixture();
        when(fixture.history.resolveSession(null, 1L, "默认回滚规则是什么？"))
                .thenReturn(45L);
        when(fixture.history.recentMessages(45L, 8)).thenReturn(List.of());
        when(fixture.retriever.retrieveWithResult(
                1L, "默认回滚规则是什么？", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(
                        List.of(chunk())));
        when(fixture.policy.decide(any())).thenReturn(answer(999L));

        assertThrows(IllegalStateException.class,
                () -> fixture.service.stream(
                        1L, null, "默认回滚规则是什么？"));

        verify(fixture.prompt, never()).render(any());
        verify(fixture.chatClient, never()).stream(anyString());
        verify(fixture.history, never()).appendMessage(
                anyLong(), anyString(), anyString());
    }

    @Test
    void duplicateRetrievedChunkIdsFailClosedBeforeModelCall() {
        Fixture fixture = fixture();
        RetrievedChunk duplicate = new RetrievedChunk(
                1L,
                11L,
                "另一份事务.md",
                "另一份事务",
                "默认回滚规则不会撤销修改。",
                2,
                0.8);
        when(fixture.history.resolveSession(null, 1L, "默认回滚规则是什么？"))
                .thenReturn(46L);
        when(fixture.history.recentMessages(46L, 8)).thenReturn(List.of());
        when(fixture.retriever.retrieveWithResult(
                1L, "默认回滚规则是什么？", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(
                        List.of(chunk(), duplicate)));
        when(fixture.policy.decide(any())).thenReturn(answer());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> fixture.service.chat(
                        1L, null, "默认回滚规则是什么？"));

        assertEquals("Retrieved evidence contains duplicate chunk IDs",
                error.getMessage());
        verify(fixture.prompt, never()).render(any());
        verify(fixture.chatClient, never()).call(anyString());
        verify(fixture.history, never()).appendMessage(
                anyLong(), anyString(), anyString());
    }

    private Fixture fixture() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        @SuppressWarnings("unchecked")
        PromptTemplate<RagPromptContext> prompt = mock(PromptTemplate.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        EvidenceDecisionPolicy policy = mock(EvidenceDecisionPolicy.class);
        RagChatService service = new RagChatService(
                retriever,
                prompt,
                chatClient,
                history,
                policy,
                new EvidenceDecisionRenderer(),
                5,
                8);
        return new Fixture(
                retriever, prompt, chatClient, history, policy, service);
    }

    private EvidenceDecisionResult clarify() {
        return decision(
                AnswerabilityDecision.CLARIFY,
                EvidenceDecisionReason.MISSING_QUESTION_SCOPE,
                List.of(),
                "请说明你所指的对象。");
    }

    private EvidenceDecisionResult answer() {
        return answer(1L);
    }

    private EvidenceDecisionResult answer(Long evidenceId) {
        return decision(
                AnswerabilityDecision.ANSWER,
                EvidenceDecisionReason.DIRECT_SUPPORT_OBSERVED,
                List.of(evidenceId),
                null);
    }

    private EvidenceDecisionResult decision(
            AnswerabilityDecision decision,
            EvidenceDecisionReason reason,
            List<Long> ids,
            String missing) {
        return new EvidenceDecisionResult(
                decision,
                reason,
                ids,
                missing,
                new EvidenceObservedSignals(
                        1, 1, 1, 1, 1.0, true,
                        false, false, false,
                        false, "LOCAL_RERANK", null, null,
                        false, RetrievalDiagnostics.EmptyReason.NONE),
                "test-policy-v1");
    }

    private RetrievedChunk chunk() {
        return new RetrievedChunk(
                1L, 10L, "事务.md", "事务",
                "默认回滚规则会撤销本次数据库修改。", 1, 0.9);
    }

    private RetrievalDiagnostics degradedDiagnostics() {
        return new RetrievalDiagnostics(
                true,
                RetrievalDiagnostics.EmptyReason.NONE,
                List.of(),
                new RetrievalDiagnostics.Rerank(
                        RerankExecutionResult.Mode.REMOTE,
                        RerankExecutionResult.Mode.LOCAL,
                        RerankExecutionResult.FallbackReason
                                .REMOTE_TECHNICAL_FAILURE,
                        RerankExecutionResult.FailureType.NONE,
                        RerankExecutionResult.SemanticEmptyReason.NONE,
                        null,
                        false,
                        null,
                        1,
                        1,
                        10L));
    }

    private record Fixture(
            KnowledgeRetriever retriever,
            PromptTemplate<RagPromptContext> prompt,
            ChatClient chatClient,
            ChatHistoryService history,
            EvidenceDecisionPolicy policy,
            RagChatService service
    ) {
    }
}
