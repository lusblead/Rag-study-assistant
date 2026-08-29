package com.rag.backend.ingestionlab.vector;

import java.time.Duration;

/** Milvus 写入已提交，但 Version 批次未在期限内达到可查询可见性。 */
public final class VectorVisibilityTimeoutException extends RuntimeException {
    private final long documentVersionId;
    private final int expectedCount;
    private final long observedCount;

    public VectorVisibilityTimeoutException(long documentVersionId,
                                            int expectedCount,
                                            long observedCount,
                                            Duration timeout) {
        this(documentVersionId, expectedCount, observedCount, timeout, null);
    }

    public VectorVisibilityTimeoutException(long documentVersionId,
                                            int expectedCount,
                                            long observedCount,
                                            Duration timeout,
                                            Throwable cause) {
        super("Vector batch did not become visible before timeout: version="
                + documentVersionId + ", expected=" + expectedCount
                + ", observed=" + observedCount + ", timeout=" + timeout,
                cause);
        this.documentVersionId = documentVersionId;
        this.expectedCount = expectedCount;
        this.observedCount = observedCount;
    }

    public long documentVersionId() {
        return documentVersionId;
    }

    public int expectedCount() {
        return expectedCount;
    }

    public long observedCount() {
        return observedCount;
    }
}
