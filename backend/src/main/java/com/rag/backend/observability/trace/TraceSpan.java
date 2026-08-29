package com.rag.backend.observability.trace;

/** 一个必须成对关闭的本地 Span scope。 */
public interface TraceSpan extends AutoCloseable {

    String traceId();

    String spanId();

    String correlationId();

    TraceCarrier carrier();

    /** 使用低基数、稳定枚举描述终态。 */
    TraceSpan result(String result);

    /** 只记录异常类型，不记录可能包含敏感内容的异常 message。 */
    TraceSpan error(Throwable error);

    /** 记录不含原始输入或外部响应的低基数事件。 */
    TraceSpan event(String eventName, String outcome);

    @Override
    void close();
}
