package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.rerank.RerankExecutionResult;

import java.util.List;
import java.util.Objects;

/**
 * 一次检索的脱敏诊断。只保留低基数状态、计数和耗时，不保存 query 或 Chunk 正文。
 */
public record RetrievalDiagnostics(
        boolean degraded,
        EmptyReason emptyReason,
        List<Source> sources,
        Rerank rerank,
        Diversity diversity
) {
    public RetrievalDiagnostics {
        emptyReason = Objects.requireNonNull(emptyReason, "emptyReason");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        rerank = Objects.requireNonNull(rerank, "rerank");
        diversity = Objects.requireNonNull(diversity, "diversity");
    }

    public RetrievalDiagnostics(
            boolean degraded,
            EmptyReason emptyReason,
            List<Source> sources,
            Rerank rerank) {
        this(degraded, emptyReason, sources, rerank,
                Diversity.unobserved(rerank.outputCandidateCount()));
    }

    public static RetrievalDiagnostics unobserved(int outputCandidateCount) {
        if (outputCandidateCount < 0) {
            throw new IllegalArgumentException(
                    "outputCandidateCount must be >= 0");
        }
        return new RetrievalDiagnostics(
                false,
                outputCandidateCount == 0
                        ? EmptyReason.UNKNOWN
                        : EmptyReason.NONE,
                List.of(),
                Rerank.unobserved(outputCandidateCount),
                Diversity.unobserved(outputCandidateCount));
    }

    public enum EmptyReason {
        NONE,
        NO_ACTIVE_VERSION,
        NO_SOURCE_CANDIDATE,
        ALL_BELOW_RERANK_THRESHOLD,
        UNKNOWN
    }

    public record Source(
            CandidateSourceType source,
            boolean succeeded,
            CandidateSourceFailureType failureType,
            int candidateCount,
            long latencyNanos
    ) {
        public Source {
            Objects.requireNonNull(source, "source");
            if (succeeded && failureType != null) {
                throw new IllegalArgumentException(
                        "successful source cannot have failureType");
            }
            if (!succeeded && failureType == null) {
                throw new IllegalArgumentException(
                        "failed source requires failureType");
            }
            if (candidateCount < 0 || latencyNanos < 0) {
                throw new IllegalArgumentException(
                        "source counts and latency must be >= 0");
            }
        }

        public static Source from(CandidateSourceDiagnostic diagnostic) {
            return new Source(
                    diagnostic.source(),
                    diagnostic.succeeded(),
                    diagnostic.failureType(),
                    diagnostic.candidateCount(),
                    diagnostic.sourceLatencyNanos());
        }
    }

    public record Rerank(
            RerankExecutionResult.Mode requestedReranker,
            RerankExecutionResult.Mode actualReranker,
            RerankExecutionResult.FallbackReason fallbackReason,
            RerankExecutionResult.FailureType terminalFailureType,
            RerankExecutionResult.SemanticEmptyReason semanticEmptyReason,
            Double appliedThreshold,
            boolean compositeEnabled,
            String compositeVersion,
            int inputCandidateCount,
            int outputCandidateCount,
            long latencyNanos
    ) {
        public Rerank {
            Objects.requireNonNull(requestedReranker, "requestedReranker");
            Objects.requireNonNull(actualReranker, "actualReranker");
            Objects.requireNonNull(fallbackReason, "fallbackReason");
            Objects.requireNonNull(terminalFailureType, "terminalFailureType");
            Objects.requireNonNull(semanticEmptyReason, "semanticEmptyReason");
            if (appliedThreshold != null && !Double.isFinite(appliedThreshold)) {
                throw new IllegalArgumentException(
                        "appliedThreshold must be finite or null");
            }
            if (inputCandidateCount < 0 || outputCandidateCount < 0
                    || latencyNanos < 0) {
                throw new IllegalArgumentException(
                        "rerank counts and latency must be >= 0");
            }
        }

        public static Rerank from(RerankExecutionResult execution) {
            return new Rerank(
                    execution.requestedReranker(),
                    execution.actualReranker(),
                    execution.fallbackReason(),
                    execution.terminalFailureType(),
                    execution.semanticEmptyReason(),
                    execution.appliedThreshold(),
                    execution.compositeEnabled(),
                    execution.compositeVersion(),
                    execution.inputCandidateCount(),
                    execution.outputCandidateCount(),
                    execution.latencyNanos());
        }

        public static Rerank unobserved(int outputCandidateCount) {
            return new Rerank(
                    RerankExecutionResult.Mode.UNKNOWN,
                    RerankExecutionResult.Mode.UNKNOWN,
                    RerankExecutionResult.FallbackReason.NONE,
                    RerankExecutionResult.FailureType.NONE,
                    RerankExecutionResult.SemanticEmptyReason.NONE,
                    null,
                    false,
                    null,
                    outputCandidateCount,
                    outputCandidateCount,
                    0L);
        }

        public boolean degraded() {
            return fallbackReason != RerankExecutionResult.FallbackReason.NONE;
        }
    }

    /**
     * Rerank 后多样性选择的脱敏聚合诊断。
     * 不保存 query、正文、逐候选分数或相似度矩阵。
     */
    public record Diversity(
            boolean enabled,
            String strategy,
            Double lambda,
            int inputCandidateCount,
            int outputCandidateCount,
            double baselineMeanRedundancy,
            double selectedMeanRedundancy,
            int baselineUniqueDocumentCount,
            int selectedUniqueDocumentCount,
            long latencyNanos
    ) {
        public Diversity {
            strategy = Objects.requireNonNull(strategy, "strategy");
            if (lambda != null && (!Double.isFinite(lambda)
                    || lambda < 0.0 || lambda > 1.0)) {
                throw new IllegalArgumentException(
                        "diversity lambda must be within [0, 1] or null");
            }
            requireUnitInterval(
                    "baselineMeanRedundancy", baselineMeanRedundancy);
            requireUnitInterval(
                    "selectedMeanRedundancy", selectedMeanRedundancy);
            if (inputCandidateCount < 0 || outputCandidateCount < 0
                    || baselineUniqueDocumentCount < 0
                    || selectedUniqueDocumentCount < 0
                    || latencyNanos < 0) {
                throw new IllegalArgumentException(
                        "diversity counts and latency must be >= 0");
            }
            if (!enabled && lambda != null) {
                throw new IllegalArgumentException(
                        "disabled diversity cannot expose lambda");
            }
        }

        public static Diversity disabled(
                int inputCandidateCount,
                int outputCandidateCount) {
            return new Diversity(
                    false,
                    "disabled",
                    null,
                    inputCandidateCount,
                    outputCandidateCount,
                    0.0,
                    0.0,
                    0,
                    0,
                    0L);
        }

        public static Diversity unobserved(int outputCandidateCount) {
            return new Diversity(
                    false,
                    "unobserved",
                    null,
                    outputCandidateCount,
                    outputCandidateCount,
                    0.0,
                    0.0,
                    0,
                    0,
                    0L);
        }

        private static void requireUnitInterval(
                String name,
                double value) {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(
                        name + " must be finite and within [0, 1]");
            }
        }
    }
}
