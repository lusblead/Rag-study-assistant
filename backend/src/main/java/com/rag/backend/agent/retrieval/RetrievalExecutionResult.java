package com.rag.backend.agent.retrieval;

import java.util.List;
import java.util.Objects;

/** 检索结果与脱敏执行诊断；技术失败继续通过异常传播。 */
public record RetrievalExecutionResult(
        List<RetrievedChunk> chunks,
        RetrievalDiagnostics diagnostics
) {
    public RetrievalExecutionResult {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static RetrievalExecutionResult unobserved(
            List<RetrievedChunk> chunks) {
        return new RetrievalExecutionResult(
                chunks,
                RetrievalDiagnostics.unobserved(chunks.size()));
    }
}
