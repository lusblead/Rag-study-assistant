package com.rag.backend.agent.grounding;

public enum CitationIntegrityReason {
    EMPTY_ANSWER,
    MALFORMED_CITATION,
    UNKNOWN_SOURCE_ID,
    MISSING_CITATION,
    COVERAGE_BELOW_REQUIRED
}
