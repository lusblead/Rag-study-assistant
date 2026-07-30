// 按 SDK 稳定类型/状态码分类，禁用异常文案 contains。
package com.rag.backend.ingestionlab.vector;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

// VectorFailureClassifier：把 Embedding、Milvus 和网络异常归一为有限稳定错误码，供重试决策和告警。
// 错误码复用第 2 章 GatewayErrorCategory 命名规范，不创造含义相近的新字符串。
public final class VectorFailureClassifier {

    private VectorFailureClassifier() { }

    // 按 SDK 稳定类型/状态码分类，不能用异常文案 contains。
    public static VectorFailure classify(Throwable error) {
        // 1. 已知永久错误——直接透传稳定码。
        if (error instanceof VectorWriteStage.PermanentVectorException permanent) {
            return VectorFailure.permanent(permanent.code());
        }

        // 2. 请求已发出但结果未知——重放必须继续使用同一 vectorId。
        if (error instanceof VectorWriteStage.VectorWriteUnknownException) {
            return VectorFailure.unknown("VECTOR_WRITE_UNKNOWN");
        }

        // 3. 剥离异步容器包装，分类必须基于真实根因。
        Throwable root = unwrap(error);

        // 4. 按根因类型逐类映射。
        if (root instanceof MilvusSdkException m) {
            return classifyHttpStatus(m.httpStatus(), root);
        }
        if (root instanceof EmbeddingSdkException e) {
            return classifyHttpStatus(e.httpStatus(), root);
        }

        // 5. 通用网络/IO 层。
        if (root instanceof SocketTimeoutException || root instanceof TimeoutException) {
            return VectorFailure.retryable("TIMEOUT", 0);
        }
        if (root instanceof IOException) {
            return VectorFailure.retryable("DOWNSTREAM_UNAVAILABLE", 0);
        }

        // 6. IllegalArgumentException 通常是配置/契约错误。
        if (root instanceof IllegalArgumentException) {
            return VectorFailure.permanent("VALIDATION");
        }

        // 7. 兜底：未知瞬时错误保守重试，但上限由 Job Lease 的 maxAttempts 控制。
        return VectorFailure.retryable("DOWNSTREAM_UNAVAILABLE", 0);
    }

    // 按 HTTP 状态码分类，不依赖异常文案。
    private static VectorFailure classifyHttpStatus(int statusCode, Throwable root) {
        return switch (statusCode) {
            // 速率受限：可重试，需尊重 Retry-After。
            case 429 -> {
                long retryAfter = extractRetryAfter(root);
                yield VectorFailure.retryable("RATE_LIMITED", retryAfter);
            }
            // 5xx：下游临时故障，可重试。
            case 500, 502, 503, 504 -> VectorFailure.retryable("DOWNSTREAM_UNAVAILABLE", 0);
            // 请求超时：结果未知，不应盲目创建新 ID。
            case 408, 524 -> VectorFailure.unknown("TIMEOUT");
            // 400、维度不匹配、schema 错误：永久配置错误。
            case 400 -> VectorFailure.permanent("VALIDATION");
            // 401、403：认证/授权永久错误。
            case 401, 403 -> VectorFailure.permanent("AUTHORIZATION");
            // 404：远端资源不存在。
            case 404 -> VectorFailure.permanent("NOT_FOUND");
            // 409：并发冲突。
            case 409 -> VectorFailure.permanent("CONFLICT");
            // 其他 4xx：保守视为永久错误。
            default -> statusCode >= 400 && statusCode < 500
                    ? VectorFailure.permanent("VALIDATION")
                    : VectorFailure.retryable("DOWNSTREAM_UNAVAILABLE", 0);
        };
    }

    private static long extractRetryAfter(Throwable root) {
        // 若 SDK 异常携带 Retry-After 头则提取；否则默认为 0（由上层退避策略决定）。
        if (root instanceof MilvusSdkException m && m.retryAfterSeconds() > 0) {
            return m.retryAfterSeconds() * 1000L;
        }
        if (root instanceof EmbeddingSdkException e && e.retryAfterSeconds() > 0) {
            return e.retryAfterSeconds() * 1000L;
        }
        return 0;
    }

    // 剥离 Future/Completion/Execution 包装，拿到真实根因。
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current.getCause() != null && current instanceof RuntimeException) {
            // RuntimeException 包装：尝试解开一层。
            Throwable inner = current.getCause();
            if (inner instanceof MilvusSdkException || inner instanceof EmbeddingSdkException
                    || inner instanceof SocketTimeoutException
                    || inner instanceof TimeoutException
                    || inner instanceof IOException) {
                return inner;
            }
        }
        return current;
    }

    // ── 分类结果 ────────────────────────────────────────────────────

    // VectorFailure：稳定错误分类，机读决策只用 errorCode 和 retryable。
    public record VectorFailure(String errorCode, boolean retryable, boolean unknown,
                                long retryAfterMs) {
        public static VectorFailure permanent(String code) {
            return new VectorFailure(code, false, false, 0);
        }

        public static VectorFailure retryable(String code, long retryAfterMs) {
            return new VectorFailure(code, true, false, retryAfterMs);
        }

        public static VectorFailure unknown(String code) {
            return new VectorFailure(code, false, true, 0);
        }
    }

    // ── SDK 异常模拟接口（真实接入时替换为 Milvus/Embedding SDK 真实异常类型）──
    // 当前项目锁定 SDK 版本后，将这两个接口替换为真实异常 catch 子句。

    // MilvusSdkException：包装 Milvus SDK 抛出的可读取 HTTP 状态码的异常。
    public static class MilvusSdkException extends RuntimeException {
        private final int httpStatus;
        private final long retryAfterSeconds;

        public MilvusSdkException(int httpStatus, String message, long retryAfterSeconds) {
            super(message);
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int httpStatus() { return httpStatus; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    // EmbeddingSdkException：包装 Embedding SDK 抛出的可读取 HTTP 状态码的异常。
    public static class EmbeddingSdkException extends RuntimeException {
        private final int httpStatus;
        private final long retryAfterSeconds;

        public EmbeddingSdkException(int httpStatus, String message, long retryAfterSeconds) {
            super(message);
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int httpStatus() { return httpStatus; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }
}
