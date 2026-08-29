package com.rag.backend.agent.grounding;

import java.util.Objects;

public record GroundedAnswerResult(
        String answer,
        GroundingDiagnostics diagnostics
) {
    public GroundedAnswerResult {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("answer must not be blank");
        }
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }
}
