package com.rag.backend.observability.performance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trace 性能指标边界。只允许 operation/result 两个稳定、低基数标签，
 * 指标后端故障不得改变业务结果。
 */
@Component
public final class TracePerformanceMetrics {
    public static final String SPAN_DURATION_METRIC =
            "rag.performance.span.duration";

    private static final Logger log =
            LoggerFactory.getLogger(TracePerformanceMetrics.class);
    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
            "chat.citation_integrity",
            "chat.claim_support",
            "chat.diversity",
            "chat.fusion",
            "chat.grounding",
            "chat.grounding.repair",
            "chat.history.write",
            "chat.http",
            "chat.llm",
            "chat.llm.http",
            "chat.llm.stream",
            "chat.policy",
            "chat.prepare",
            "chat.rerank",
            "chat.retrieval",
            "chat.retrieval.dense",
            "chat.retrieval.scope",
            "chat.retrieval.sources",
            "chat.sse.complete",
            "chat.sse.delta",
            "chat.sse.emitter",
            "chat.sse.error",
            "chat.sse.subscribe",
            "chat.sse.terminal",
            "chat.turn",
            "ingestion.durable.route",
            "ingestion.executor",
            "ingestion.http",
            "ingestion.job.recovery",
            "ingestion.outbox.dispatch",
            "ingestion.stage.activate",
            "ingestion.stage.chunk",
            "ingestion.stage.embed_write",
            "ingestion.stage.parse",
            "ingestion.stage.verify",
            "ingestion.stage.visibility",
            "ingestion.submit",
            "ingestion.submit.reuse",
            "ingestion.worker.attempt",
            "unknown");
    private static final Set<String> ALLOWED_RESULTS = Set.of(
            "accepted",
            "answer",
            "cancelled",
            "claim_lost",
            "clarify",
            "client_error",
            "completed",
            "disabled",
            "dispatched",
            "error",
            "failed",
            "ignored",
            "invalid",
            "job_not_found",
            "lease_lost",
            "lease_not_acquired",
            "legacy_recovery",
            "not_applicable",
            "not_supported",
            "on_error",
            "prepared",
            "recovered",
            "refuse",
            "rejected",
            "repaired",
            "replayed",
            "retry",
            "retry_exhausted",
            "reused",
            "send_failed",
            "sent",
            "server_error",
            "success",
            "supported",
            "timeout",
            "unknown",
            "valid");

    private final MeterRegistry registry;
    private final AtomicBoolean registryFailureLogged = new AtomicBoolean();

    public TracePerformanceMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void recordSpanDuration(
            String operation,
            String result,
            long durationNanos) {
        String safeOperation = bucket(operation, ALLOWED_OPERATIONS);
        String safeResult = bucket(result, ALLOWED_RESULTS);
        long safeDurationNanos = Math.max(0L, durationNanos);
        try {
            registry.timer(
                            SPAN_DURATION_METRIC,
                            Tags.of(
                                    "operation", safeOperation,
                                    "result", safeResult))
                    .record(safeDurationNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException registryFailure) {
            if (registryFailureLogged.compareAndSet(false, true)) {
                try {
                    log.warn("trace_performance_meter_registry_failed");
                } catch (RuntimeException ignored) {
                    // 指标与日志后端同时失败时，调用方仍必须继续业务路径。
                }
            }
        }
    }

    private static String bucket(String value, Set<String> allowed) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "unknown";
    }
}
