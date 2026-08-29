package com.rag.backend.observability.performance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracePerformanceMetricsTest {

    @Test
    void endSpanRecordsOnlySafeOperationAndResultTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TracePerformanceMetrics metrics =
                new TracePerformanceMetrics(registry);
        AtomicInteger sample = new AtomicInteger();
        long[] nanos = {10L, 55L};
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(),
                ignored -> { },
                () -> nanos[sample.getAndIncrement()],
                metrics);

        try (TraceSpan span = traces.startRoot(
                "chat.retrieval", "business-correlation-71")) {
            span.result("success");
        }

        Timer timer = registry.get(
                        TracePerformanceMetrics.SPAN_DURATION_METRIC)
                .tags("operation", "chat.retrieval", "result", "success")
                .timer();
        assertEquals(1L, timer.count());
        assertEquals(45.0, timer.totalTime(TimeUnit.NANOSECONDS));

        Map<String, String> tags = timer.getId().getTags().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        io.micrometer.core.instrument.Tag::getKey,
                        io.micrometer.core.instrument.Tag::getValue));
        assertEquals(
                Map.of(
                        "operation", "chat.retrieval",
                        "result", "success"),
                tags);
        assertFalse(tags.containsKey("traceId"));
        assertFalse(tags.containsKey("correlationId"));
        assertFalse(tags.containsKey("query"));
        assertFalse(tags.containsKey("message"));
    }

    @Test
    void syntacticallyValidButUnapprovedTagValuesCollapseToUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TracePerformanceMetrics metrics =
                new TracePerformanceMetrics(registry);

        metrics.recordSpanDuration(
                "customer.123456789",
                "tenant_987654321",
                7L);

        Timer timer = registry.get(
                        TracePerformanceMetrics.SPAN_DURATION_METRIC)
                .tags("operation", "unknown", "result", "unknown")
                .timer();
        assertEquals(1L, timer.count());
        assertEquals(7.0, timer.totalTime(TimeUnit.NANOSECONDS));
    }

    @Test
    void registryFailureCannotChangeSpanResultOrCleanup() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.timer(
                eq(TracePerformanceMetrics.SPAN_DURATION_METRIC),
                any(Tags.class)))
                .thenThrow(new IllegalStateException("registry unavailable"));
        TracePerformanceMetrics metrics =
                new TracePerformanceMetrics(registry);
        AtomicInteger sample = new AtomicInteger();
        long[] nanos = {100L, 110L};
        TraceContextService traces = TraceContextService.forTesting(
                new FixedIds(),
                ignored -> { },
                () -> nanos[sample.getAndIncrement()],
                metrics);

        assertDoesNotThrow(() -> {
            try (TraceSpan span = traces.startRoot("chat.http", null)) {
                span.result("success");
            }
        });
    }

    @Test
    void repeatedRegistryFailuresEmitOnlyOneWarning() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.timer(
                eq(TracePerformanceMetrics.SPAN_DURATION_METRIC),
                any(Tags.class)))
                .thenThrow(new IllegalStateException("registry unavailable"));
        TracePerformanceMetrics metrics =
                new TracePerformanceMetrics(registry);
        Logger logger = (Logger) LoggerFactory.getLogger(
                TracePerformanceMetrics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            metrics.recordSpanDuration("chat.http", "success", 1L);
            metrics.recordSpanDuration("chat.http", "success", 2L);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        long warnings = appender.list.stream()
                .filter(event -> "trace_performance_meter_registry_failed"
                        .equals(event.getFormattedMessage()))
                .count();
        assertEquals(1L, warnings);
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
