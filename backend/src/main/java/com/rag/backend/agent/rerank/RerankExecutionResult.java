package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.common.BizException;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Rerank 的脱敏执行记录。这里只保存低基数状态、计数和耗时，
 * 不保存 query、Chunk 正文、API Key 或远程响应。
 */
public record RerankExecutionResult(
        List<RetrievedChunk> chunks,
        Mode requestedReranker,
        Mode actualReranker,
        List<Attempt> attempts,
        FallbackReason fallbackReason,
        FailureType terminalFailureType,
        Double appliedThreshold,
        SemanticEmptyReason semanticEmptyReason,
        boolean compositeEnabled,
        String compositeVersion,
        int inputCandidateCount,
        long latencyNanos
) {
    public RerankExecutionResult {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        requestedReranker = Objects.requireNonNull(
                requestedReranker, "requestedReranker");
        actualReranker = Objects.requireNonNull(
                actualReranker, "actualReranker");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        fallbackReason = Objects.requireNonNull(
                fallbackReason, "fallbackReason");
        terminalFailureType = Objects.requireNonNull(
                terminalFailureType, "terminalFailureType");
        semanticEmptyReason = Objects.requireNonNull(
                semanticEmptyReason, "semanticEmptyReason");
        if (appliedThreshold != null && !Double.isFinite(appliedThreshold)) {
            throw new IllegalArgumentException(
                    "appliedThreshold must be finite or null");
        }
        if (inputCandidateCount < 0) {
            throw new IllegalArgumentException(
                    "inputCandidateCount must be >= 0");
        }
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be >= 0");
        }
        if (compositeEnabled
                && (compositeVersion == null || compositeVersion.isBlank())) {
            throw new IllegalArgumentException(
                    "compositeVersion is required when composite is enabled");
        }
    }

    public int outputCandidateCount() {
        return chunks.size();
    }

    public boolean degraded() {
        return fallbackReason != FallbackReason.NONE;
    }

    public boolean thresholdApplied() {
        return appliedThreshold != null;
    }

    public static RerankExecutionResult unobserved(
            List<RetrievedChunk> input,
            List<RetrievedChunk> output,
            long latencyNanos) {
        return new RerankExecutionResult(
                output,
                Mode.UNKNOWN,
                Mode.UNKNOWN,
                List.of(),
                FallbackReason.NONE,
                FailureType.NONE,
                null,
                output.isEmpty() && !input.isEmpty()
                        ? SemanticEmptyReason.UNKNOWN
                        : SemanticEmptyReason.NONE,
                false,
                null,
                input.size(),
                Math.max(0L, latencyNanos));
    }

    public enum Mode {
        NONE,
        LOCAL,
        REMOTE,
        ORIGINAL,
        UNKNOWN
    }

    public enum AttemptOutcome {
        SUCCESS,
        TECHNICAL_FAILURE,
        BYPASSED
    }

    public enum FailureType {
        NONE,
        TIMEOUT,
        HTTP_ERROR,
        NETWORK_ERROR,
        INVALID_RESPONSE,
        CONFIGURATION,
        INTERRUPTED,
        EXECUTION_ERROR
    }

    public enum FallbackReason {
        NONE,
        REMOTE_TECHNICAL_FAILURE,
        LOCAL_TECHNICAL_FAILURE,
        REMOTE_AND_LOCAL_TECHNICAL_FAILURE
    }

    public enum SemanticEmptyReason {
        NONE,
        INPUT_EMPTY,
        ALL_BELOW_THRESHOLD,
        UNKNOWN
    }

    public record Attempt(
            Mode reranker,
            AttemptOutcome outcome,
            FailureType failureType,
            long latencyNanos
    ) {
        public Attempt {
            Objects.requireNonNull(reranker, "reranker");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(failureType, "failureType");
            if (latencyNanos < 0) {
                throw new IllegalArgumentException(
                        "attempt latencyNanos must be >= 0");
            }
            if (outcome == AttemptOutcome.TECHNICAL_FAILURE
                    && failureType == FailureType.NONE) {
                throw new IllegalArgumentException(
                        "technical failure attempt requires failureType");
            }
            if (outcome != AttemptOutcome.TECHNICAL_FAILURE
                    && failureType != FailureType.NONE) {
                throw new IllegalArgumentException(
                        "successful or bypassed attempt cannot have failureType");
            }
        }
    }

    /** 远程或本地 Reranker 的安全、可分类技术失败。 */
    public static class TechnicalFailure extends BizException {
        private final FailureType failureType;

        public TechnicalFailure(FailureType failureType, String safeMessage) {
            super(500, safeMessage);
            if (failureType == null || failureType == FailureType.NONE) {
                throw new IllegalArgumentException(
                        "technical failure requires failureType");
            }
            this.failureType = failureType;
        }

        public FailureType failureType() {
            return failureType;
        }
    }

    /** fail-closed 时携带已经形成的脱敏执行记录。 */
    public static class ExecutionFailure extends BizException {
        private final RerankExecutionResult execution;

        public ExecutionFailure(
                String safeMessage,
                RerankExecutionResult execution,
                Throwable cause) {
            super(500, safeMessage);
            this.execution = Objects.requireNonNull(execution, "execution");
            if (cause != null) {
                initCause(cause);
            }
        }

        public RerankExecutionResult execution() {
            return execution;
        }
    }
}
