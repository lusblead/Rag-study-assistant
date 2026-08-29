package com.rag.backend.observability;

import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceHttpFilter;
import com.rag.backend.observability.trace.TraceSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextServiceTest {
    private static final String TRACE_ONE = "1".repeat(32);
    private static final String TRACE_TWO = "2".repeat(32);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void nestedSpansShareTraceAndRestoreMdc() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = service(events);

        try (TraceSpan root = traces.startRoot("chat.http", "chat-1")) {
            assertEquals(TRACE_ONE, root.traceId());
            assertTrue(root.carrier().traceparent().endsWith("-01"));
            assertEquals(TRACE_ONE, MDC.get("traceId"));
            assertEquals("chat-1", MDC.get("correlationId"));

            try (TraceSpan child = traces.startSpan("chat.retrieval")) {
                assertEquals(root.traceId(), child.traceId());
                assertNotEquals(root.spanId(), child.spanId());
                assertEquals(child.spanId(), MDC.get("spanId"));
            }

            assertEquals(root.spanId(), MDC.get("spanId"));
        }

        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("spanId"));
        assertNull(MDC.get("correlationId"));
        TraceContextService.TraceEvent childStart = events.stream()
                .filter(event -> event.type() == TraceContextService.EventType.START)
                .filter(event -> "chat.retrieval".equals(event.operation()))
                .findFirst()
                .orElseThrow();
        TraceContextService.TraceEvent rootStart = events.stream()
                .filter(event -> event.type() == TraceContextService.EventType.START)
                .filter(event -> "chat.http".equals(event.operation()))
                .findFirst()
                .orElseThrow();
        assertEquals(rootStart.spanId(), childStart.parentSpanId());
    }

    @Test
    void carrierContinuesTraceButCreatesNewSpanAndCleansThread() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = service(events);
        TraceCarrier carrier;
        String parentSpan;
        try (TraceSpan submit = traces.startRoot("ingestion.submit", "job-1")) {
            carrier = submit.carrier();
            parentSpan = submit.spanId();
        }

        traces.wrap(carrier, "ingestion.worker", () -> {
            assertEquals(TRACE_ONE, MDC.get("traceId"));
            assertEquals("job-1", MDC.get("correlationId"));
        }).run();

        assertNull(MDC.get("traceId"));
        TraceContextService.TraceEvent worker = events.stream()
                .filter(event -> event.type() == TraceContextService.EventType.START)
                .filter(event -> "ingestion.worker".equals(event.operation()))
                .findFirst()
                .orElseThrow();
        assertEquals(TRACE_ONE, worker.traceId());
        assertEquals(parentSpan, worker.parentSpanId());
    }

    @Test
    void invalidCarrierStartsNewIndependentTrace() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = service(events);

        try (TraceSpan span = traces.continueOrStart(
                new TraceCarrier("not-a-traceparent", "safe-correlation"),
                "chat.http",
                null)) {
            assertEquals(TRACE_ONE, span.traceId());
            assertEquals("safe-correlation", span.correlationId());
        }

        assertFalse(events.isEmpty());
    }

    @Test
    void legalV00TraceFlagsArePreservedAcrossContinuationAndChildren() {
        String remoteTraceId = "a".repeat(32);
        String remoteSpanId = "b".repeat(16);

        for (String flags : List.of("00", "01")) {
            List<TraceContextService.TraceEvent> events = new ArrayList<>();
            TraceContextService traces = TraceContextService.forTesting(
                    new FixedIds(), events::add);
            TraceCarrier incoming = new TraceCarrier(
                    "00-" + remoteTraceId + "-" + remoteSpanId + "-" + flags,
                    "job-flags-" + flags);

            try (TraceSpan continued = traces.continueOrStart(
                    incoming, "ingestion.outbox.dispatch", null)) {
                assertEquals(remoteTraceId, continued.traceId());
                assertEquals(
                        "00-" + remoteTraceId + "-"
                                + continued.spanId() + "-" + flags,
                        continued.carrier().traceparent());

                try (TraceSpan child = traces.startSpan("ingestion.executor")) {
                    assertEquals(remoteTraceId, child.traceId());
                    assertEquals(
                            "00-" + remoteTraceId + "-"
                                    + child.spanId() + "-" + flags,
                            child.carrier().traceparent());
                }
            }

            TraceContextService.TraceEvent continuedStart = events.stream()
                    .filter(event -> event.type()
                            == TraceContextService.EventType.START)
                    .filter(event -> "ingestion.outbox.dispatch"
                            .equals(event.operation()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(remoteSpanId, continuedStart.parentSpanId());
            assertNull(MDC.get("traceId"));
        }
    }

    @Test
    void invalidFlagsAndUnsupportedVersionsFallBackToLocalRootPolicy() {
        String remoteTraceId = "a".repeat(32);
        String remoteSpanId = "b".repeat(16);
        List<String> invalidTraceparents = List.of(
                "00-" + remoteTraceId + "-" + remoteSpanId + "-0g",
                "00-" + remoteTraceId + "-" + remoteSpanId + "-0",
                "01-" + remoteTraceId + "-" + remoteSpanId + "-01");

        for (String traceparent : invalidTraceparents) {
            TraceContextService traces = TraceContextService.forTesting(
                    new FixedIds(), ignored -> { });
            try (TraceSpan recovered = traces.continueOrStart(
                    new TraceCarrier(traceparent, "safe-correlation"),
                    "ingestion.job.recovery",
                    null)) {
                assertEquals(TRACE_ONE, recovered.traceId());
                assertNotEquals(remoteTraceId, recovered.traceId());
                assertTrue(recovered.carrier().traceparent().endsWith("-01"));
                assertEquals("safe-correlation", recovered.correlationId());
            }
            assertNull(MDC.get("traceId"));
        }
    }

    @Test
    void missingOrInvalidCorrelationNeverFallsBackToTraceId() {
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(), ignored -> { });

        try (TraceSpan missing = traces.startRoot("ingestion.http", null)) {
            assertNull(missing.correlationId());
            assertNull(missing.carrier().correlationId());
            assertNull(MDC.get("correlationId"));
        }
        try (TraceSpan invalid = traces.startRoot(
                "ingestion.http", "contains whitespace")) {
            assertNull(invalid.correlationId());
            assertNotEquals(invalid.traceId(), invalid.correlationId());
            assertNull(MDC.get("correlationId"));
        }
    }

    @Test
    void jobCorrelationBeginsAtCaptureAndPropagatesDownstream() {
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(), ignored -> { });
        TraceCarrier jobCarrier;

        try (TraceSpan request = traces.startRoot("ingestion.http", null)) {
            assertNull(request.correlationId());
            jobCarrier = traces.capture("job-71");
            assertEquals("job-71", jobCarrier.correlationId());
            assertNull(request.correlationId());
        }

        try (TraceSpan worker = traces.continueOrStart(
                jobCarrier, "ingestion.worker.attempt", "job-71")) {
            assertEquals("job-71", worker.correlationId());
            assertEquals("job-71", MDC.get("correlationId"));
        }
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void genericHttpFilterNeverTrustsExternalCorrelationHeader()
            throws Exception {
        for (String uri : List.of(
                "/api/agent/chat",
                "/api/documents/71/ingest")) {
            TraceContextService traces = TraceContextService.forTesting(
                    new FixedIds(), ignored -> { });
            TraceHttpFilter filter = new TraceHttpFilter(traces);
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", uri);
            request.addHeader(
                    TraceHttpFilter.CORRELATION_ID_HEADER,
                    "externally-forged-job-id");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                assertNull(MDC.get("correlationId"));
            });

            assertNull(response.getHeader(
                    TraceHttpFilter.CORRELATION_ID_HEADER));
            assertNull(MDC.get("correlationId"));
        }
    }

    @Test
    void chatHttpAlwaysStartsIndependentRootAndOnlyLinksIncomingTrace()
            throws Exception {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = service(events);
        TraceHttpFilter filter = new TraceHttpFilter(traces);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/agent/chat");
        request.addHeader(
                TraceHttpFilter.TRACEPARENT_HEADER,
                "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01");
        request.addHeader(
                TraceHttpFilter.CORRELATION_ID_HEADER, "shared-business-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertEquals(TRACE_ONE, MDC.get("traceId"));
            assertNotEquals("a".repeat(32), MDC.get("traceId"));
            assertNull(MDC.get("correlationId"));
        });

        assertEquals(TRACE_ONE, response.getHeader(
                TraceHttpFilter.TRACE_ID_HEADER));
        assertTrue(TraceContextService.isValidTraceparent(
                response.getHeader(TraceHttpFilter.TRACEPARENT_HEADER)));
        assertNull(response.getHeader(
                TraceHttpFilter.CORRELATION_ID_HEADER));
        assertNull(MDC.get("traceId"));
        TraceContextService.TraceEvent link = events.stream()
                .filter(event -> event.type() == TraceContextService.EventType.LINK)
                .findFirst()
                .orElseThrow();
        assertEquals(TRACE_ONE, link.traceId());
        assertEquals("a".repeat(32), link.detail());
    }

    @Test
    void httpStatusAndContextPathProduceTruthfulTerminalResult()
            throws Exception {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = service(events);
        TraceHttpFilter filter = new TraceHttpFilter(traces);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/rag/api/agent/chat");
        request.setContextPath("/rag");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(503));

        TraceContextService.TraceEvent end = events.stream()
                .filter(event -> event.type() == TraceContextService.EventType.END)
                .filter(event -> "chat.http".equals(event.operation()))
                .findFirst()
                .orElseThrow();
        assertEquals("server_error", end.result());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void sseHttpRootOnlyClaimsDispatchNotStreamCompletion()
            throws Exception {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = service(events);
        TraceHttpFilter filter = new TraceHttpFilter(traces);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/agent/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> { });

        TraceContextService.TraceEvent end = events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> "chat.http".equals(event.operation()))
                .findFirst()
                .orElseThrow();
        assertEquals("dispatched", end.result());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void httpFilterErrorStillEmitsErrorTerminalAndCleansMdc()
            throws Exception {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = service(events);
        TraceHttpFilter filter = new TraceHttpFilter(traces);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/agent/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalStateException.class, () -> filter.doFilter(
                request, response,
                (ignoredRequest, ignoredResponse) -> {
                    throw new IllegalStateException("sensitive-canary");
                }));

        TraceContextService.TraceEvent end = events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> "chat.http".equals(event.operation()))
                .findFirst()
                .orElseThrow();
        assertEquals("error", end.result());
        assertEquals("illegalstateexception", end.detail());
        assertTrue(TraceContextService.isValidTraceparent(
                response.getHeader(TraceHttpFilter.TRACEPARENT_HEADER)));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("spanId"));
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void sinkFailureAndPreexistingMdcCannotPreventCleanup() {
        MDC.put("traceId", "preexisting-trace");
        MDC.put("spanId", "preexisting-span");
        MDC.put("correlationId", "preexisting-correlation");
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(),
                ignored -> { throw new IllegalStateException("sink unavailable"); });

        try (TraceSpan span = traces.startRoot("chat.http", "chat-1")) {
            span.result("success");
            assertEquals(TRACE_ONE, MDC.get("traceId"));
        }

        assertEquals("preexisting-trace", MDC.get("traceId"));
        assertEquals("preexisting-span", MDC.get("spanId"));
        assertEquals("preexisting-correlation", MDC.get("correlationId"));
    }

    @Test
    void outOfOrderCloseIsRejectedWithoutDestroyingActiveChild() {
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(), ignored -> { });
        TraceSpan root = traces.startRoot("chat.http", "chat-1");
        TraceSpan child = traces.startSpan("chat.retrieval");

        assertThrows(IllegalStateException.class, root::close);
        assertEquals(child.spanId(), MDC.get("spanId"));
        child.close();
        assertEquals(root.spanId(), MDC.get("spanId"));
        root.close();
        root.close();
        assertNull(MDC.get("traceId"));
    }

    private TraceContextService service(
            List<TraceContextService.TraceEvent> events) {
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
                        return "%016x".formatted(spanIndex.incrementAndGet());
                    }
                },
                events::add);
    }

    private static final class FixedIds
            implements TraceContextService.IdGenerator {
        private final AtomicInteger spanIndex = new AtomicInteger();

        @Override
        public String nextTraceId() {
            return TRACE_ONE;
        }

        @Override
        public String nextSpanId() {
            return "%016x".formatted(spanIndex.incrementAndGet());
        }
    }
}
