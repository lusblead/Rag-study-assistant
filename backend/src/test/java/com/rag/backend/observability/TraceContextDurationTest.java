package com.rag.backend.observability;

import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceContextDurationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void onlyEndEventCarriesDeterministicMonotonicDuration() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(),
                events::add,
                clock(100L, 145L));

        try (TraceSpan span = traces.startRoot(
                "chat.retrieval", "chat-1")) {
            span.event("candidate.collection", "observed");
            traces.link(
                    new TraceCarrier(
                            "00-" + "a".repeat(32)
                                    + "-" + "b".repeat(16) + "-01",
                            null),
                    "upstream");
            span.result("success");
        }

        assertEquals(
                45L,
                events.stream()
                        .filter(event -> event.type()
                                == TraceContextService.EventType.END)
                        .findFirst()
                        .orElseThrow()
                        .durationNanos());
        events.stream()
                .filter(event -> event.type()
                        != TraceContextService.EventType.END)
                .forEach(event -> assertEquals(0L, event.durationNanos()));
    }

    @Test
    void negativeClockDeltaIsClampedToZero() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(),
                events::add,
                clock(200L, 150L));

        try (TraceSpan ignored = traces.startRoot("chat.http", null)) {
            // close records the second, lower sample
        }

        TraceContextService.TraceEvent end = events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .findFirst()
                .orElseThrow();
        assertEquals(0L, end.durationNanos());
    }

    @Test
    void monotonicClockFailureFallsBackToZeroWithoutBreakingScope() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        LongSupplier brokenClock = () -> {
            throw new IllegalStateException("clock unavailable");
        };
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(), events::add, brokenClock);

        assertDoesNotThrow(() -> {
            try (TraceSpan span = traces.startRoot("chat.http", null)) {
                span.result("success");
            }
        });

        assertEquals(
                0L,
                events.stream()
                        .filter(event -> event.type()
                                == TraceContextService.EventType.END)
                        .findFirst()
                        .orElseThrow()
                        .durationNanos());
        assertEquals(null, MDC.get("traceId"));
    }

    private static LongSupplier clock(long... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[index.getAndIncrement()];
    }

    private static final class FixedIds
            implements TraceContextService.IdGenerator {
        private final AtomicInteger span = new AtomicInteger();

        @Override
        public String nextTraceId() {
            return "1".repeat(32);
        }

        @Override
        public String nextSpanId() {
            return "%016x".formatted(span.incrementAndGet());
        }
    }
}
