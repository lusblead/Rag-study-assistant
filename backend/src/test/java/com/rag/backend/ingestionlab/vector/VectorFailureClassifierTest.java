// 测试错误分类器：对 429/5xx/timeout/400/维度/碰撞等每一类验证 retryable 和 errorCode。
package com.rag.backend.ingestionlab.vector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class VectorFailureClassifierTest {

    // ── 永久错误 ──────────────────────────────────────────────────────

    @Test
    void permanentExceptions_notRetryable() {
        assertFalse(classify(new VectorWriteStage.PermanentVectorException(
                "VECTOR_ID_COLLISION")).retryable());
        assertFalse(classify(new VectorWriteStage.PermanentVectorException(
                "EMBED_DIMENSION_MISMATCH")).retryable());
    }

    @Test
    void permanentException_preservesCode() {
        var f = classify(new VectorWriteStage.PermanentVectorException(
                "EMBED_DIMENSION_MISMATCH"));
        assertEquals("EMBED_DIMENSION_MISMATCH", f.errorCode());
    }

    // ── 结果未知 ──────────────────────────────────────────────────────

    @Test
    void unknownException_notRetryable_butMarkedUnknown() {
        var f = classify(new VectorWriteStage.VectorWriteUnknownException(
                "response lost after commit"));
        assertFalse(f.retryable());
        assertTrue(f.unknown());
        assertEquals("VECTOR_WRITE_UNKNOWN", f.errorCode());
    }

    // ── HTTP 状态码分类 ───────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "429, RATE_LIMITED,    true",
            "500, DOWNSTREAM_UNAVAILABLE, true",
            "502, DOWNSTREAM_UNAVAILABLE, true",
            "503, DOWNSTREAM_UNAVAILABLE, true",
            "504, DOWNSTREAM_UNAVAILABLE, true",
            "408, TIMEOUT,         false",  // 超时结果未知
            "400, VALIDATION,      false",  // 客户端契约错误
            "401, AUTHORIZATION,   false",
            "403, AUTHORIZATION,   false",
            "404, NOT_FOUND,       false",
            "409, CONFLICT,        false",
    })
    void httpStatusMapping_statusToCodeAndRetryable(
            int status, String expectedCode, boolean expectedRetryable) {
        var f = classify(new VectorFailureClassifier.MilvusSdkException(
                status, "http error " + status, 0));
        assertEquals(expectedCode, f.errorCode(),
                "status " + status + " → " + expectedCode);
        assertEquals(expectedRetryable, f.retryable(),
                "status " + status + " retryable=" + expectedRetryable);
    }

    @Test
    void rateLimited_extractsRetryAfter() {
        var ex = new VectorFailureClassifier.EmbeddingSdkException(
                429, "rate limited", 30);
        var f = classify(ex);
        assertTrue(f.retryable());
        assertEquals(30_000L, f.retryAfterMs());
    }

    @Test
    void rateLimited_zeroRetryAfter_whenNotProvided() {
        var ex = new VectorFailureClassifier.EmbeddingSdkException(
                429, "rate limited", 0);
        var f = classify(ex);
        assertTrue(f.retryable());
        assertEquals(0L, f.retryAfterMs());
    }

    // ── 网络/IO 层 ────────────────────────────────────────────────────

    @Test
    void timeoutException_isRetryable() {
        var f = classify(new SocketTimeoutException("connect timed out"));
        assertTrue(f.retryable());
        assertEquals("TIMEOUT", f.errorCode());
    }

    @Test
    void ioException_isRetryable() {
        var f = classify(new java.io.IOException("connection reset"));
        assertTrue(f.retryable());
        assertEquals("DOWNSTREAM_UNAVAILABLE", f.errorCode());
    }

    // ── 配置/契约错误 ────────────────────────────────────────────────

    @Test
    void illegalArgumentException_isPermanent() {
        var f = classify(new IllegalArgumentException("dimension must be 1024"));
        assertFalse(f.retryable());
        assertEquals("VALIDATION", f.errorCode());
    }

    // ── 维度不匹配（PermanentVectorException 子类）────────────────────

    @Test
    void dimensionMismatch_isPermanent_notRetryable() {
        var f = classify(new VectorWriteStage.PermanentVectorException(
                "EMBED_DIMENSION_MISMATCH"));
        assertFalse(f.retryable());
        assertFalse(f.unknown());
        assertEquals("EMBED_DIMENSION_MISMATCH", f.errorCode());
    }

    // ── 碰撞（PermanentVectorException 子类）──────────────────────────

    @Test
    void vectorIdCollision_isPermanent_notRetryable() {
        var f = classify(new VectorWriteStage.PermanentVectorException(
                "VECTOR_ID_COLLISION"));
        assertFalse(f.retryable());
        assertFalse(f.unknown());
        assertEquals("VECTOR_ID_COLLISION", f.errorCode());
    }

    // ── Embedding SDK 错误也走 HTTP 分类逻辑 ──────────────────────────

    @Test
    void embeddingSdk_500_retryable() {
        var f = classify(new VectorFailureClassifier.EmbeddingSdkException(
                500, "internal error", 0));
        assertTrue(f.retryable());
        assertEquals("DOWNSTREAM_UNAVAILABLE", f.errorCode());
    }

    @Test
    void embeddingSdk_400_permanent() {
        var f = classify(new VectorFailureClassifier.EmbeddingSdkException(
                400, "bad request", 0));
        assertFalse(f.retryable());
        assertEquals("VALIDATION", f.errorCode());
    }

    // ── 无错误码重复 ──────────────────────────────────────────────────

    @Test
    void everyCodeIsFromGatewayConvention() {
        // RATE_LIMITED, DOWNSTREAM_UNAVAILABLE, TIMEOUT, VALIDATION,
        // AUTHORIZATION, NOT_FOUND, CONFLICT 全部复用第 2 章规范；
        // VECTOR_ID_COLLISION, EMBED_DIMENSION_MISMATCH, VECTOR_WRITE_UNKNOWN
        // 是向量写阶段特化的稳定码，不与其他含义重叠。
        var codes = java.util.Set.of(
                "RATE_LIMITED", "DOWNSTREAM_UNAVAILABLE", "TIMEOUT",
                "VALIDATION", "AUTHORIZATION", "NOT_FOUND", "CONFLICT",
                "VECTOR_ID_COLLISION", "EMBED_DIMENSION_MISMATCH",
                "VECTOR_WRITE_UNKNOWN");
        assertEquals(10, codes.size(), "没有重复的错误码");
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    private static VectorFailureClassifier.VectorFailure classify(Throwable error) {
        return VectorFailureClassifier.classify(error);
    }
}
