package com.rag.backend.agent.chat;

import com.rag.backend.agent.controller.RagChatController;
import com.rag.backend.agent.evidence.AnswerabilityDecision;
import com.rag.backend.agent.evidence.EvidenceDecisionPolicy;
import com.rag.backend.agent.evidence.EvidenceDecisionReason;
import com.rag.backend.agent.evidence.EvidenceDecisionRenderer;
import com.rag.backend.agent.evidence.EvidenceDecisionResult;
import com.rag.backend.agent.evidence.EvidenceObservedSignals;
import com.rag.backend.agent.grounding.CitationIntegrityValidator;
import com.rag.backend.agent.grounding.ClaimSupportEvaluator;
import com.rag.backend.agent.grounding.GroundedAnswerGenerator;
import com.rag.backend.agent.grounding.GroundingFailureRenderer;
import com.rag.backend.agent.grounding.GroundingProperties;
import com.rag.backend.agent.grounding.GroundingRepairPromptTemplate;
import com.rag.backend.agent.grounding.GroundingValidator;
import com.rag.backend.agent.grounding.UncalibratedSemanticClaimJudge;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatMetadata;
import com.rag.backend.agent.model.RagChatRequest;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagChatStreamResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.common.BizException;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatTracePropagationTest {
    private static final String TRACE_ONE = "1".repeat(32);
    private static final String TRACE_TWO = "2".repeat(32);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void synchronousAnswerKeepsParentChildTraceAcrossGroundingLayers() {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(events, null);
        ServiceFixture fixture = serviceFixture(
                traces, answerDecision(), true);
        when(fixture.chatClient().call(anyString()))
                .thenReturn("本店支持退货。[S1]");

        RagChatResponse response;
        try (TraceSpan root = traces.startRoot("chat.http", "chat-test")) {
            response = fixture.service().chat(1L, null, "是否支持退货？");
            root.result("success");
        }

        assertEquals("本店支持退货。[S1]", response.answer());
        assertParent(events, "chat.turn", "chat.http");
        assertParent(events, "chat.retrieval", "chat.turn");
        assertParent(events, "chat.policy", "chat.turn");
        assertParent(events, "chat.grounding", "chat.turn");
        assertParent(events, "chat.llm", "chat.grounding");
        assertParent(events, "chat.citation_integrity", "chat.grounding");
        assertParent(events, "chat.claim_support", "chat.grounding");
        assertTrue(events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.START)
                .allMatch(event -> TRACE_ONE.equals(event.traceId())));
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
    }

    @Test
    void refuseHasPolicyAndHistoryButNoLlmOrGroundingSpan() {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(events, null);
        ServiceFixture fixture = serviceFixture(
                traces, refuseDecision(), true);

        try (TraceSpan root = traces.startRoot("chat.http", "chat-refuse")) {
            fixture.service().chat(1L, null, "无法回答的问题");
        }

        verify(fixture.chatClient(), never()).call(anyString());
        verify(fixture.chatClient(), never()).stream(anyString());
        assertTrue(hasStart(events, "chat.policy"));
        assertTrue(hasStart(events, "chat.history.write"));
        assertFalse(hasStart(events, "chat.llm"));
        assertFalse(hasStart(events, "chat.grounding"));
        assertFalse(hasStart(events, "chat.citation_integrity"));
        assertFalse(hasStart(events, "chat.claim_support"));
    }

    @Test
    void sseSignalsContinueTraceAcrossAsyncThreadAndLeaveNoMdc() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(events, terminal);
        ExecutorService executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "chat-trace-test-sse"));
        Scheduler scheduler = Schedulers.fromExecutorService(executor);
        try {
            RagChatService service = mock(RagChatService.class);
            ChatHistoryService history = mock(ChatHistoryService.class);
            Flux<String> source = Flux.just("A", "B").publishOn(scheduler);
            when(service.stream(1L, null, "stream-question"))
                    .thenReturn(new RagChatStreamResponse(
                            77L, List.of(), metadata(refuseDecision()), source));
            RagChatController controller = new RagChatController(
                    service, history, traces);

            try (TraceSpan root = traces.startRoot(
                    "chat.http", "chat-stream")) {
                controller.stream(request("stream-question"));
            }

            assertTrue(terminal.await(5, TimeUnit.SECONDS));
            assertTrue(hasStart(events, "chat.sse.subscribe"));
            assertEquals(2, events.stream()
                    .filter(event -> event.type()
                            == TraceContextService.EventType.START)
                    .filter(event -> "chat.sse.delta".equals(
                            event.operation()))
                    .count());
            assertTrue(events.stream()
                    .filter(event -> event.operation().startsWith("chat.sse"))
                    .allMatch(event -> TRACE_ONE.equals(event.traceId())));
            assertEquals("completed", terminalEnd(events).result());
            assertNull(executor.submit(() -> MDC.get(
                    TraceContextService.TRACE_ID_MDC_KEY)).get(5,
                    TimeUnit.SECONDS));
            assertNull(CompletableFuture.supplyAsync(() -> MDC.get(
                    TraceContextService.TRACE_ID_MDC_KEY)).get(5,
                    TimeUnit.SECONDS));
        } finally {
            scheduler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void sseErrorRecordsRealErrorTerminalOnTheSameTrace() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(events, terminal);
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(service.stream(1L, null, "failing-stream"))
                .thenReturn(new RagChatStreamResponse(
                        78L,
                        List.of(),
                        metadata(refuseDecision()),
                        Flux.error(new IllegalStateException(
                                "must-not-enter-trace"))));
        RecordingRagChatController controller =
                new RecordingRagChatController(
                service, history, traces);

        try (TraceSpan root = traces.startRoot("chat.http", "chat-error")) {
            controller.stream(request("failing-stream"));
        }

        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        TraceContextService.TraceEvent end = terminalEnd(events);
        assertEquals(TRACE_ONE, end.traceId());
        assertEquals("error", end.result());
        assertEquals("illegalstateexception", end.detail());
        assertSafeErrorPayload(controller.errorPayload.get(),
                "must-not-enter-trace");
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
    }

    @Test
    void ssePrepareErrorHasOneSanitizedTerminalOnTheRequestTrace()
            throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(events, terminal);
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(service.stream(1L, null, "prepare-failure"))
                .thenThrow(new IllegalStateException(
                        "sensitive-prepare-canary"));
        RecordingRagChatController controller =
                new RecordingRagChatController(
                        service, history, traces);

        try (TraceSpan root = traces.startRoot(
                "chat.http", "chat-prepare-failure")) {
            controller.stream(request("prepare-failure"));
        }

        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        TraceContextService.TraceEvent end = terminalEnd(events);
        assertEquals(TRACE_ONE, end.traceId());
        assertEquals("error", end.result());
        assertEquals("illegalstateexception", end.detail());
        assertEquals(1, terminalEndCount(events));
        assertSafeErrorPayload(controller.errorPayload.get(),
                "sensitive-prepare-canary");
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
    }

    @Test
    void ssePrepareBusinessRejectionUsesGenericPayloadAndTracesBizException()
            throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(events, terminal);
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(service.stream(1L, null, "business-rejection"))
                .thenThrow(new BizException(
                        404, "sensitive-session-canary"));
        RecordingRagChatController controller =
                new RecordingRagChatController(
                        service, history, traces);

        try (TraceSpan root = traces.startRoot(
                "chat.http", "chat-business-rejection")) {
            controller.stream(request("business-rejection"));
        }

        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        TraceContextService.TraceEvent end = terminalEnd(events);
        assertEquals(TRACE_ONE, end.traceId());
        assertEquals("error", end.result());
        assertEquals("bizexception", end.detail());
        assertEquals(1, terminalEndCount(events));
        assertSafeErrorPayload(controller.errorPayload.get(),
                "sensitive-session-canary");
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void initialEventSendFailureStopsBeforeReturnedPublisherSubscription(
            int failOnAttempt) throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(events, terminal);
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        AtomicBoolean returnedPublisherSubscribed = new AtomicBoolean();
        AtomicBoolean returnedPublisherCancelled = new AtomicBoolean();
        Flux<String> source = Flux.defer(() -> {
            returnedPublisherSubscribed.set(true);
            return Flux.just("must-not-be-subscribed");
        }).doOnCancel(() -> returnedPublisherCancelled.set(true));
        when(service.stream(1L, null, "initial-send-failure"))
                .thenReturn(new RagChatStreamResponse(
                        82L,
                        List.of(),
                        metadata(refuseDecision()),
                        source));
        FailingSseEmitter emitter = new FailingSseEmitter(failOnAttempt);
        RagChatController controller = new TestRagChatController(
                service, history, traces, emitter);

        try (TraceSpan root = traces.startRoot(
                "chat.http", "chat-initial-send-failure")) {
            controller.stream(request("initial-send-failure"));
        }

        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
        assertEquals("send_failed", terminalEnd(events).result());
        assertEquals(1, terminalEndCount(events));
        assertFalse(returnedPublisherSubscribed.get());
        assertFalse(returnedPublisherCancelled.get());
        assertEquals(failOnAttempt, emitter.sendAttempts.get());
        assertEquals(1, emitter.completeCalls.get());
        assertFalse(hasStart(events, "chat.sse.subscribe"));
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
    }

    @Test
    void sseErrorEventSendFailureIsTerminalSendFailedExactlyOnce()
            throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch subscribeEnded = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(
                events, terminal, subscribeEnded);
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(service.stream(1L, null, "error-send-failure"))
                .thenReturn(new RagChatStreamResponse(
                        81L,
                        List.of(),
                        metadata(refuseDecision()),
                        Flux.error(new IllegalStateException(
                                "sensitive-error-canary"))));
        FailingSseEmitter emitter = new FailingSseEmitter(4);
        RagChatController controller = new TestRagChatController(
                service, history, traces, emitter);

        try (TraceSpan root = traces.startRoot(
                "chat.http", "chat-error-send-failure")) {
            controller.stream(request("error-send-failure"));
        }

        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
        assertTrue(subscribeEnded.await(5, TimeUnit.SECONDS));
        TraceContextService.TraceEvent end = terminalEnd(events);
        assertEquals("send_failed", end.result());
        assertEquals("illegalstateexception", end.detail());
        assertEquals(1, terminalEndCount(events));
        assertEquals(4, emitter.sendAttempts.get());
        assertEquals(1, emitter.completeCalls.get());
    }

    @Test
    void doneSendFailureIsTerminalSendFailedExactlyOnce() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch subscribeEnded = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(
                events, terminal, subscribeEnded);
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(service.stream(1L, null, "done-send-failure"))
                .thenReturn(new RagChatStreamResponse(
                        79L,
                        List.of(),
                        metadata(refuseDecision()),
                        Flux.just("complete answer")));
        FailingSseEmitter emitter = new FailingSseEmitter(5);
        RagChatController controller = new TestRagChatController(
                service, history, traces, emitter);

        try (TraceSpan root = traces.startRoot(
                "chat.http", "chat-done-send-failure")) {
            controller.stream(request("done-send-failure"));
        }

        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
        assertTrue(subscribeEnded.await(5, TimeUnit.SECONDS));
        assertEquals("send_failed", terminalEnd(events).result());
        assertEquals("send_failed",
                end(events, "chat.sse.complete").result());
        assertEquals(1, terminalEndCount(events));
        assertEquals(5, emitter.sendAttempts.get());
        assertEquals(1, emitter.completeCalls.get());
    }

    @Test
    void deltaSendFailureCompletesEmitterAndCancelsUpstreamExactlyOnce()
            throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch subscribeEnded = new CountDownLatch(1);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = traceService(
                events, terminal, subscribeEnded);
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        Flux<String> source = Flux.just("first", "second")
                .doOnCancel(() -> upstreamCancelled.set(true));
        when(service.stream(1L, null, "delta-send-failure"))
                .thenReturn(new RagChatStreamResponse(
                        80L,
                        List.of(),
                        metadata(refuseDecision()),
                        source));
        FailingSseEmitter emitter = new FailingSseEmitter(4);
        RagChatController controller = new TestRagChatController(
                service, history, traces, emitter);

        try (TraceSpan root = traces.startRoot(
                "chat.http", "chat-delta-send-failure")) {
            controller.stream(request("delta-send-failure"));
        }

        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
        assertTrue(subscribeEnded.await(5, TimeUnit.SECONDS));
        assertEquals("send_failed", terminalEnd(events).result());
        assertEquals(1, terminalEndCount(events));
        assertTrue(upstreamCancelled.get());
        assertEquals(4, emitter.sendAttempts.get());
        assertEquals(1, emitter.completeCalls.get());
        assertFalse(hasStart(events, "chat.sse.complete"));
    }

    private ServiceFixture serviceFixture(
            TraceContextService traces,
            EvidenceDecisionResult decision,
            boolean groundingEnabled) {
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
        when(history.resolveSession(null, 1L, "无法回答的问题"))
                .thenReturn(78L);
        when(history.recentMessages(77L, 8)).thenReturn(List.of());
        when(history.recentMessages(78L, 8)).thenReturn(List.of());
        when(retriever.retrieveWithResult(1L, "是否支持退货？", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(
                        List.of(chunk)));
        when(retriever.retrieveWithResult(1L, "无法回答的问题", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(
                        List.of(chunk)));
        when(policy.decide(org.mockito.ArgumentMatchers.any()))
                .thenReturn(decision);
        when(prompt.render(org.mockito.ArgumentMatchers.any()))
                .thenReturn("prompt");

        GroundingProperties properties = new GroundingProperties();
        properties.setEnabled(groundingEnabled);
        GroundingValidator validator = new GroundingValidator(
                new CitationIntegrityValidator(),
                new ClaimSupportEvaluator(
                        new UncalibratedSemanticClaimJudge()),
                traces);
        GroundedAnswerGenerator generator = new GroundedAnswerGenerator(
                properties,
                validator,
                new GroundingRepairPromptTemplate(),
                new GroundingFailureRenderer(),
                traces);
        RagChatService service = new RagChatService(
                retriever, prompt, chatClient, history, policy,
                new EvidenceDecisionRenderer(), generator,
                5, 8, traces);
        return new ServiceFixture(service, chatClient);
    }

    private EvidenceDecisionResult answerDecision() {
        return decision(AnswerabilityDecision.ANSWER,
                EvidenceDecisionReason.DIRECT_SUPPORT_OBSERVED,
                List.of(1L));
    }

    private EvidenceDecisionResult refuseDecision() {
        return decision(AnswerabilityDecision.REFUSE,
                EvidenceDecisionReason.NO_RETRIEVED_EVIDENCE,
                List.of());
    }

    private EvidenceDecisionResult decision(
            AnswerabilityDecision decision,
            EvidenceDecisionReason reason,
            List<Long> ids) {
        return new EvidenceDecisionResult(
                decision,
                reason,
                ids,
                null,
                new EvidenceObservedSignals(
                        ids.isEmpty() ? 0 : 1,
                        ids.isEmpty() ? 0 : 1,
                        ids.isEmpty() ? 0 : 1,
                        ids.isEmpty() ? 0 : 1,
                        ids.isEmpty() ? 0.0 : 1.0,
                        !ids.isEmpty(),
                        false, false, false,
                        false, null, null, null,
                        false,
                        ids.isEmpty()
                                ? RetrievalDiagnostics.EmptyReason
                                .NO_SOURCE_CANDIDATE
                                : RetrievalDiagnostics.EmptyReason.NONE),
                "trace-test-policy-v1");
    }

    private RagChatMetadata metadata(EvidenceDecisionResult decision) {
        return new RagChatMetadata(
                decision,
                RetrievalExecutionResult.unobserved(List.of()).diagnostics());
    }

    private RagChatRequest request(String question) {
        RagChatRequest request = new RagChatRequest();
        request.setCourseId(1L);
        request.setQuestion(question);
        return request;
    }

    private TraceContextService traceService(
            List<TraceContextService.TraceEvent> events,
            CountDownLatch terminal) {
        return traceService(events, terminal, null);
    }

    private TraceContextService traceService(
            List<TraceContextService.TraceEvent> events,
            CountDownLatch terminal,
            CountDownLatch subscribeEnded) {
        AtomicInteger traceIndex = new AtomicInteger();
        AtomicInteger spanIndex = new AtomicInteger();
        return TraceContextService.forTesting(
                new TraceContextService.IdGenerator() {
                    @Override
                    public String nextTraceId() {
                        return traceIndex.getAndIncrement() == 0
                                ? TRACE_ONE
                                : TRACE_TWO;
                    }

                    @Override
                    public String nextSpanId() {
                        return "%016x".formatted(
                                spanIndex.incrementAndGet());
                    }
                },
                event -> {
                    events.add(event);
                    if (terminal != null
                            && event.type()
                            == TraceContextService.EventType.END
                            && "chat.sse.terminal".equals(
                            event.operation())) {
                        terminal.countDown();
                    }
                    if (subscribeEnded != null
                            && event.type()
                            == TraceContextService.EventType.END
                            && "chat.sse.subscribe".equals(
                            event.operation())) {
                        subscribeEnded.countDown();
                    }
                });
    }

    private void assertParent(
            List<TraceContextService.TraceEvent> events,
            String childOperation,
            String parentOperation) {
        TraceContextService.TraceEvent child = start(events, childOperation);
        TraceContextService.TraceEvent parent = start(events, parentOperation);
        assertEquals(parent.spanId(), child.parentSpanId());
        assertEquals(parent.traceId(), child.traceId());
    }

    private boolean hasStart(
            List<TraceContextService.TraceEvent> events,
            String operation) {
        return events.stream().anyMatch(event -> event.type()
                == TraceContextService.EventType.START
                && operation.equals(event.operation()));
    }

    private TraceContextService.TraceEvent start(
            List<TraceContextService.TraceEvent> events,
            String operation) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.START)
                .filter(event -> operation.equals(event.operation()))
                .findFirst()
                .orElseThrow();
    }

    private TraceContextService.TraceEvent terminalEnd(
            List<TraceContextService.TraceEvent> events) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> "chat.sse.terminal".equals(
                        event.operation()))
                .findFirst()
                .orElseThrow();
    }

    private long terminalEndCount(
            List<TraceContextService.TraceEvent> events) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> "chat.sse.terminal".equals(
                        event.operation()))
                .count();
    }

    private TraceContextService.TraceEvent end(
            List<TraceContextService.TraceEvent> events,
            String operation) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> operation.equals(event.operation()))
                .findFirst()
                .orElseThrow();
    }

    private void assertSafeErrorPayload(
            Object value, String sensitiveCanary) {
        assertTrue(value instanceof Map<?, ?>);
        Map<?, ?> payload = (Map<?, ?>) value;
        assertEquals("CHAT_STREAM_FAILED", payload.get("code"));
        assertEquals("聊天处理失败，请稍后重试", payload.get("message"));
        assertFalse(payload.toString().contains(sensitiveCanary));
    }

    private static final class TestRagChatController
            extends RagChatController {
        private final SseEmitter emitter;

        private TestRagChatController(
                RagChatService ragChatService,
                ChatHistoryService chatHistoryService,
                TraceContextService traces,
                SseEmitter emitter) {
            super(ragChatService, chatHistoryService, traces);
            this.emitter = emitter;
        }

        @Override
        protected SseEmitter createEmitter() {
            return emitter;
        }
    }

    private static final class RecordingRagChatController
            extends RagChatController {
        private final AtomicReference<Object> errorPayload =
                new AtomicReference<>();

        private RecordingRagChatController(
                RagChatService ragChatService,
                ChatHistoryService chatHistoryService,
                TraceContextService traces) {
            super(ragChatService, chatHistoryService, traces);
        }

        @Override
        protected boolean send(
                SseEmitter emitter, String eventName, Object data) {
            if ("error".equals(eventName)) {
                errorPayload.set(data);
            }
            return super.send(emitter, eventName, data);
        }
    }

    private static final class FailingSseEmitter extends SseEmitter {
        private final int failOnAttempt;
        private final AtomicInteger sendAttempts = new AtomicInteger();
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final CountDownLatch completed = new CountDownLatch(1);

        private FailingSseEmitter(int failOnAttempt) {
            super(0L);
            this.failOnAttempt = failOnAttempt;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (sendAttempts.incrementAndGet() == failOnAttempt) {
                throw new IOException("forced SSE send failure");
            }
            super.send(builder);
        }

        @Override
        public void complete() {
            completeCalls.incrementAndGet();
            completed.countDown();
            super.complete();
        }
    }

    private record ServiceFixture(
            RagChatService service,
            ChatClient chatClient) {
    }
}
