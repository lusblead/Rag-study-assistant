package com.rag.backend.observability.trace;

import com.rag.backend.observability.performance.TracePerformanceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Trace/MDC 边界：生成真实父子 Span、传播 W3C traceparent，并在 scope
 * 关闭时恢复线程原状态。
 *
 * <p>本组件不冒充外部 Trace 后端。生产证据是结构化 Span 日志；确定性测试可以
 * 注入事件接收器验证父子关系、错误终态与线程清理。</p>
 */
@Component
public class TraceContextService {
    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String SPAN_ID_MDC_KEY = "spanId";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(TraceContextService.class);
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,79}$");
    private static final Pattern SAFE_CORRELATION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);
    /** 本地新建 root 默认采样；从外部 carrier 延续时不覆盖其 trace-flags。 */
    private static final String DEFAULT_LOCAL_TRACE_FLAGS = "01";

    private final ThreadLocal<SpanContext> current = new ThreadLocal<>();
    private final IdGenerator ids;
    private final Consumer<TraceEvent> eventSink;
    private final LongSupplier nanoTime;
    private final TracePerformanceMetrics performanceMetrics;

    public TraceContextService() {
        this(
                new SecureIdGenerator(),
                TraceContextService::logEvent,
                System::nanoTime,
                null);
    }

    /** Spring 装配使用 Micrometer；手工构造仍保持无指标依赖的兼容行为。 */
    @Autowired
    public TraceContextService(TracePerformanceMetrics performanceMetrics) {
        this(
                new SecureIdGenerator(),
                TraceContextService::logEvent,
                System::nanoTime,
                Objects.requireNonNull(performanceMetrics, "performanceMetrics"));
    }

    private TraceContextService(
            IdGenerator ids,
            Consumer<TraceEvent> eventSink,
            LongSupplier nanoTime,
            TracePerformanceMetrics performanceMetrics) {
        this.ids = Objects.requireNonNull(ids, "ids");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.performanceMetrics = performanceMetrics;
    }

    /** 仅供确定性测试使用，不注册为 Spring Bean。 */
    public static TraceContextService forTesting(
            IdGenerator ids, Consumer<TraceEvent> eventSink) {
        return forTesting(ids, eventSink, System::nanoTime);
    }

    /** 仅供确定性耗时测试使用，不注册为 Spring Bean。 */
    public static TraceContextService forTesting(
            IdGenerator ids,
            Consumer<TraceEvent> eventSink,
            LongSupplier nanoTime) {
        return new TraceContextService(ids, eventSink, nanoTime, null);
    }

    /** 仅供确定性指标测试使用，不注册为 Spring Bean。 */
    public static TraceContextService forTesting(
            IdGenerator ids,
            Consumer<TraceEvent> eventSink,
            LongSupplier nanoTime,
            TracePerformanceMetrics performanceMetrics) {
        return new TraceContextService(
                ids,
                eventSink,
                nanoTime,
                Objects.requireNonNull(performanceMetrics, "performanceMetrics"));
    }

    public TraceSpan startRoot(String operation, String correlationId) {
        String traceId = requireTraceId(ids.nextTraceId());
        return open(
                operation,
                traceId,
                null,
                DEFAULT_LOCAL_TRACE_FLAGS,
                sanitizeCorrelation(correlationId));
    }

    /**
     * 从合法 carrier 创建子 Span；carrier 缺失或非法时创建新的 recovery root。
     */
    public TraceSpan continueOrStart(
            TraceCarrier carrier, String operation, String fallbackCorrelationId) {
        ParsedParent parent = parse(carrier == null ? null : carrier.traceparent());
        String requestedCorrelation = carrier == null
                ? fallbackCorrelationId
                : firstNonBlank(carrier.correlationId(), fallbackCorrelationId);
        if (parent == null) {
            return startRoot(operation, requestedCorrelation);
        }
        return open(
                operation,
                parent.traceId(),
                parent.spanId(),
                parent.traceFlags(),
                sanitizeCorrelation(requestedCorrelation));
    }

    /** 当前存在 Span 时创建真实子节点，否则创建独立 root。 */
    public TraceSpan startSpan(String operation) {
        return startSpan(operation, null);
    }

    /** 当前存在 Span 时可只覆盖 correlationId，不改变 Trace 父子关系。 */
    public TraceSpan startSpan(String operation, String correlationId) {
        SpanContext parent = current.get();
        if (parent == null) {
            return startRoot(operation, correlationId);
        }
        return open(
                operation,
                parent.traceId(),
                parent.spanId(),
                parent.traceFlags(),
                selectCorrelation(correlationId, parent.correlationId()));
    }

    public Optional<TraceCarrier> currentCarrier() {
        SpanContext context = current.get();
        return context == null
                ? Optional.empty()
                : Optional.of(context.carrier());
    }

    public TraceCarrier capture(String correlationId) {
        SpanContext context = current.get();
        if (context == null) {
            return TraceCarrier.empty().withCorrelationId(
                    sanitizeCorrelation(correlationId));
        }
        return new TraceCarrier(
                context.traceparent(),
                selectCorrelation(correlationId, context.correlationId()));
    }

    /** 仅校验本组件支持的 W3C v00 traceparent，不暴露或记录原始值。 */
    public static boolean isValidTraceparent(String traceparent) {
        return parse(traceparent) != null;
    }

    /** 捕获调用线程的不可变 carrier；执行线程总会在 finally 中关闭 scope。 */
    public Runnable wrapCurrent(String operation, Runnable task) {
        return wrap(capture(null), operation, task);
    }

    public Runnable wrap(TraceCarrier carrier, String operation, Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (TraceSpan span = continueOrStart(carrier, operation,
                    carrier == null ? null : carrier.correlationId())) {
                try {
                    task.run();
                    span.result("success");
                } catch (RuntimeException | Error error) {
                    span.error(error);
                    throw error;
                }
            }
        };
    }

    public <T> T inSpan(String operation, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        try (TraceSpan span = startSpan(operation)) {
            try {
                T result = action.get();
                span.result("success");
                return result;
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    /** 显式 link 只记录目标 traceId；不把另一条 Trace 变成当前父节点。 */
    public void link(TraceCarrier linked, String relation) {
        SpanContext context = current.get();
        ParsedParent parent = parse(linked == null ? null : linked.traceparent());
        if (context == null || parent == null) {
            return;
        }
        emit(new TraceEvent(
                EventType.LINK,
                safeName(relation),
                context.traceId(),
                context.spanId(),
                context.parentSpanId(),
                context.correlationId(),
                "linked",
                parent.traceId(),
                0L));
    }

    private TraceSpan open(
            String operation,
            String traceId,
            String parentSpanId,
            String traceFlags,
            String correlationId) {
        String safeOperation = safeName(operation);
        SpanContext previous = current.get();
        SpanContext context = new SpanContext(
                traceId,
                requireSpanId(ids.nextSpanId()),
                parentSpanId,
                traceFlags,
                correlationId,
                safeOperation);
        TimeSample startedAt = sampleNanoTime();
        MdcSnapshot previousMdc = MdcSnapshot.capture();
        current.set(context);
        putMdc(context);
        emit(context.event(EventType.START, "started", null, 0L));
        return new ActiveTraceSpan(context, previous, previousMdc, startedAt);
    }

    private final class ActiveTraceSpan implements TraceSpan {
        private final SpanContext context;
        private final SpanContext previous;
        private final MdcSnapshot previousMdc;
        private final TimeSample startedAt;
        private final Thread ownerThread = Thread.currentThread();
        private final AtomicBoolean closed = new AtomicBoolean();
        private String result = "unknown";
        private String errorCode;

        private ActiveTraceSpan(
                SpanContext context,
                SpanContext previous,
                MdcSnapshot previousMdc,
                TimeSample startedAt) {
            this.context = context;
            this.previous = previous;
            this.previousMdc = previousMdc;
            this.startedAt = startedAt;
        }

        @Override
        public String traceId() {
            return context.traceId();
        }

        @Override
        public String spanId() {
            return context.spanId();
        }

        @Override
        public String correlationId() {
            return context.correlationId();
        }

        @Override
        public TraceCarrier carrier() {
            return context.carrier();
        }

        @Override
        public TraceSpan result(String value) {
            this.result = safeOutcome(value, "success");
            return this;
        }

        @Override
        public TraceSpan error(Throwable error) {
            this.result = "error";
            this.errorCode = error == null
                    ? "unknown"
                    : safeOutcome(error.getClass().getSimpleName(), "unknown");
            return this;
        }

        @Override
        public TraceSpan event(String eventName, String outcome) {
            emit(context.event(
                    EventType.EVENT,
                    safeOutcome(outcome, "observed"),
                    safeName(eventName),
                    0L));
            return this;
        }

        @Override
        public void close() {
            if (closed.get()) {
                return;
            }
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                        "TraceSpan must close on the thread that opened it; propagate a carrier instead");
            }
            if (current.get() != context) {
                throw new IllegalStateException(
                        "TraceSpan scopes must close in LIFO order");
            }
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            emit(context.event(
                    EventType.END,
                    result,
                    errorCode,
                    durationSince(startedAt)));
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
            previousMdc.restore();
        }
    }

    private static void putMdc(SpanContext context) {
        MDC.put(TRACE_ID_MDC_KEY, context.traceId());
        MDC.put(SPAN_ID_MDC_KEY, context.spanId());
        putOrRemove(CORRELATION_ID_MDC_KEY, context.correlationId());
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static ParsedParent parse(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
        if (!matcher.matches()
                || ZERO_TRACE_ID.equals(matcher.group(1))
                || ZERO_SPAN_ID.equals(matcher.group(2))) {
            return null;
        }
        return new ParsedParent(
                matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private static String safeName(String value) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return SAFE_NAME.matcher(normalized).matches() ? normalized : "unknown";
    }

    private static String safeOutcome(String value, String fallback) {
        return safeName(value == null ? fallback : value);
    }

    private static String sanitizeCorrelation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_CORRELATION.matcher(trimmed).matches() ? trimmed : null;
    }

    /** 缺失显式值时继承；非法显式值必须丢弃，不能伪装成父关联。 */
    private static String selectCorrelation(String explicit, String inherited) {
        if (explicit == null || explicit.isBlank()) {
            return sanitizeCorrelation(inherited);
        }
        return sanitizeCorrelation(explicit);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String requireTraceId(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}") || ZERO_TRACE_ID.equals(value)) {
            throw new IllegalArgumentException("trace ID generator returned an invalid value");
        }
        return value;
    }

    private static String requireSpanId(String value) {
        if (value == null || !value.matches("[0-9a-f]{16}") || ZERO_SPAN_ID.equals(value)) {
            throw new IllegalArgumentException("span ID generator returned an invalid value");
        }
        return value;
    }

    private static void logEvent(TraceEvent event) {
        switch (event.type()) {
            case START -> log.info(
                    "trace_span_start traceId={} spanId={} parentSpanId={} "
                            + "correlationId={} operation={} result={}",
                    event.traceId(), event.spanId(), event.parentSpanId(),
                    event.correlationId(), event.operation(), event.result());
            case END -> log.info(
                    "trace_span_end traceId={} spanId={} correlationId={} "
                            + "operation={} result={} errorCode={} durationNanos={}",
                    event.traceId(), event.spanId(), event.correlationId(),
                    event.operation(), event.result(), event.detail(),
                    event.durationNanos());
            case EVENT -> log.info(
                    "trace_span_event traceId={} spanId={} correlationId={} "
                            + "operation={} result={} event={}",
                    event.traceId(), event.spanId(), event.correlationId(),
                    event.operation(), event.result(), event.detail());
            case LINK -> log.info(
                    "trace_span_link traceId={} spanId={} correlationId={} "
                            + "relation={} linkedTraceId={}",
                    event.traceId(), event.spanId(), event.correlationId(),
                    event.operation(), event.detail());
        }
    }

    /** 可观察性故障不得改变业务结果或阻止 finally 清理。 */
    private void emit(TraceEvent event) {
        try {
            eventSink.accept(event);
        } catch (RuntimeException sinkFailure) {
            try {
                log.warn("trace_event_sink_failed eventType={}", event.type());
            } catch (RuntimeException ignored) {
                // 日志后端同样不可用时保持业务路径与 scope 清理可用。
            }
        }
        if (event.type() == EventType.END && performanceMetrics != null) {
            try {
                performanceMetrics.recordSpanDuration(
                        event.operation(),
                        event.result(),
                        event.durationNanos());
            } catch (RuntimeException metricsFailure) {
                try {
                    log.warn("trace_performance_metrics_failed");
                } catch (RuntimeException ignored) {
                    // 指标和日志同时不可用时，业务与 scope 清理仍必须继续。
                }
            }
        }
    }

    private TimeSample sampleNanoTime() {
        try {
            return new TimeSample(true, nanoTime.getAsLong());
        } catch (RuntimeException clockFailure) {
            try {
                log.warn("trace_monotonic_clock_failed");
            } catch (RuntimeException ignored) {
                // 单调时钟探针不可用时降级为零耗时，不改变业务结果。
            }
            return TimeSample.unavailable();
        }
    }

    private long durationSince(TimeSample startedAt) {
        TimeSample finishedAt = sampleNanoTime();
        if (!startedAt.available() || !finishedAt.available()) {
            return 0L;
        }
        return Math.max(0L, finishedAt.value() - startedAt.value());
    }

    public interface IdGenerator {
        String nextTraceId();

        String nextSpanId();
    }

    public enum EventType {
        START,
        END,
        EVENT,
        LINK
    }

    public record TraceEvent(
            EventType type,
            String operation,
            String traceId,
            String spanId,
            String parentSpanId,
            String correlationId,
            String result,
            String detail,
            long durationNanos) {
        public TraceEvent {
            if (durationNanos < 0L) {
                throw new IllegalArgumentException(
                        "durationNanos must be >= 0");
            }
        }
    }

    private record ParsedParent(
            String traceId,
            String spanId,
            String traceFlags) {
    }

    private record SpanContext(
            String traceId,
            String spanId,
            String parentSpanId,
            String traceFlags,
            String correlationId,
            String operation) {

        private String traceparent() {
            return "00-" + traceId + "-" + spanId + "-" + traceFlags;
        }

        private TraceCarrier carrier() {
            return new TraceCarrier(traceparent(), correlationId);
        }

        private TraceEvent event(
                EventType type,
                String result,
                String detail,
                long durationNanos) {
            return new TraceEvent(
                    type,
                    operation,
                    traceId,
                    spanId,
                    parentSpanId,
                    correlationId,
                    result,
                    detail,
                    durationNanos);
        }
    }

    private record TimeSample(boolean available, long value) {
        private static TimeSample unavailable() {
            return new TimeSample(false, 0L);
        }
    }

    private record MdcSnapshot(String traceId, String spanId, String correlationId) {
        private static MdcSnapshot capture() {
            return new MdcSnapshot(
                    MDC.get(TRACE_ID_MDC_KEY),
                    MDC.get(SPAN_ID_MDC_KEY),
                    MDC.get(CORRELATION_ID_MDC_KEY));
        }

        private void restore() {
            putOrRemove(TRACE_ID_MDC_KEY, traceId);
            putOrRemove(SPAN_ID_MDC_KEY, spanId);
            putOrRemove(CORRELATION_ID_MDC_KEY, correlationId);
        }
    }

    private static final class SecureIdGenerator implements IdGenerator {
        private final SecureRandom random = new SecureRandom();

        @Override
        public String nextTraceId() {
            return randomHex(16);
        }

        @Override
        public String nextSpanId() {
            return randomHex(8);
        }

        private String randomHex(int bytes) {
            byte[] value = new byte[bytes];
            random.nextBytes(value);
            return HexFormat.of().formatHex(value);
        }
    }
}
