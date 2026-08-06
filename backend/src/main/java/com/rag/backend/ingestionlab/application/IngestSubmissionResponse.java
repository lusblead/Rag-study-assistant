package com.rag.backend.ingestionlab.application;

public record IngestSubmissionResponse(
        long documentVersionId,
        String jobId,
        boolean reused) {

    public static IngestSubmissionResponse from(
            IngestApplicationService.Submission value) {
        return new IngestSubmissionResponse(
                value.documentVersionId(),
                value.jobId(),
                value.reused());
    }
}