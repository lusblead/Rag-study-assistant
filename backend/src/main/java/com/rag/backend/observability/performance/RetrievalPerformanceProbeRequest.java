package com.rag.backend.observability.performance;

/** 本地检索性能探针的最小请求。该对象不会进入日志或指标标签。 */
public record RetrievalPerformanceProbeRequest(
        Long courseId,
        String query,
        Integer topK) {
}
