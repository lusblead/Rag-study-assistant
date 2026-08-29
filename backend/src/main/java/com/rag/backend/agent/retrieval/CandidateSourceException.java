package com.rag.backend.agent.retrieval;

import java.util.Objects;

/** 候选源可显式提供给收集器的失败类别。 */
public class CandidateSourceException extends RuntimeException {
    private final CandidateSourceFailureType failureType;

    public CandidateSourceException(
            CandidateSourceFailureType failureType,
            String message) {
        this(failureType, message, null);
    }

    public CandidateSourceException(
            CandidateSourceFailureType failureType,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failureType = Objects.requireNonNull(failureType, "failureType");
    }

    public CandidateSourceFailureType failureType() {
        return failureType;
    }
}
