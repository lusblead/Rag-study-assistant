package com.rag.backend.observability.performance;

import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 检索性能探针的脱敏投影。只保留低基数状态、聚合计数和阶段耗时，
 * 不包含 query、courseId、Chunk、文档或其他业务 ID。
 */
public record RetrievalPerformanceProbeResponse(
        String status,
        boolean degraded,
        String emptyReason,
        long sourceCandidateCount,
        int rerankInputCount,
        int resultCount,
        List<Stage> stages) {

    public RetrievalPerformanceProbeResponse {
        status = Objects.requireNonNull(status, "status");
        emptyReason = Objects.requireNonNull(emptyReason, "emptyReason");
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        if (sourceCandidateCount < 0L
                || rerankInputCount < 0
                || resultCount < 0) {
            throw new IllegalArgumentException(
                    "probe response counts must be >= 0");
        }
    }

    public static RetrievalPerformanceProbeResponse from(
            RetrievalExecutionResult execution) {
        Objects.requireNonNull(execution, "execution");
        RetrievalDiagnostics diagnostics = execution.diagnostics();
        List<Stage> stages = new ArrayList<>();
        long sourceCandidateCount = 0L;

        for (RetrievalDiagnostics.Source source : diagnostics.sources()) {
            sourceCandidateCount += source.candidateCount();
            stages.add(new Stage(
                    "source." + source.source().name()
                            .toLowerCase(Locale.ROOT),
                    source.succeeded() ? "success" : "failed",
                    source.candidateCount(),
                    source.latencyNanos()));
        }

        RetrievalDiagnostics.Rerank rerank = diagnostics.rerank();
        stages.add(new Stage(
                "rerank",
                rerankStatus(rerank),
                rerank.outputCandidateCount(),
                rerank.latencyNanos()));

        RetrievalDiagnostics.Diversity diversity = diagnostics.diversity();
        stages.add(new Stage(
                "diversity",
                diversityStatus(diversity),
                diversity.outputCandidateCount(),
                diversity.latencyNanos()));

        String status = diagnostics.degraded()
                ? "degraded"
                : execution.chunks().isEmpty() ? "empty" : "success";
        return new RetrievalPerformanceProbeResponse(
                status,
                diagnostics.degraded(),
                diagnostics.emptyReason().name().toLowerCase(Locale.ROOT),
                sourceCandidateCount,
                rerank.inputCandidateCount(),
                execution.chunks().size(),
                stages);
    }

    private static String rerankStatus(RetrievalDiagnostics.Rerank rerank) {
        if (rerank.terminalFailureType()
                != RerankExecutionResult.FailureType.NONE) {
            return "failed";
        }
        return rerank.degraded() ? "degraded" : "success";
    }

    private static String diversityStatus(
            RetrievalDiagnostics.Diversity diversity) {
        if ("unobserved".equals(diversity.strategy())) {
            return "unobserved";
        }
        return diversity.enabled() ? "enabled" : "disabled";
    }

    public record Stage(
            String stage,
            String status,
            int candidateCount,
            long latencyNanos) {
        public Stage {
            stage = Objects.requireNonNull(stage, "stage");
            status = Objects.requireNonNull(status, "status");
            if (candidateCount < 0 || latencyNanos < 0L) {
                throw new IllegalArgumentException(
                        "stage count and latency must be >= 0");
            }
        }
    }
}
