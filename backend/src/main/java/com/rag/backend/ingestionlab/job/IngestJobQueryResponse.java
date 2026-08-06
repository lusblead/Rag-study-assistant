package com.rag.backend.ingestionlab.job;

public record IngestJobQueryResponse(
        String jobId,
        Long documentId,
        Long documentVersionId,
        String jobType,
        String state,
        int attempt,
        int maxAttempts,
        String errorCode
) {
}
