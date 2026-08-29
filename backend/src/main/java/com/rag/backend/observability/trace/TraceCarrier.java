package com.rag.backend.observability.trace;

/**
 * 可跨线程或持久化边界传播的最小 Trace 载体。
 *
 * <p>只携带 W3C {@code traceparent} 与不含业务正文的关联键；不传播 baggage，
 * 避免把用户输入、路径或凭据复制到队列和数据库。</p>
 */
public record TraceCarrier(String traceparent, String correlationId) {

    public static TraceCarrier empty() {
        return new TraceCarrier(null, null);
    }

    public TraceCarrier withCorrelationId(String value) {
        return new TraceCarrier(traceparent, value);
    }
}
