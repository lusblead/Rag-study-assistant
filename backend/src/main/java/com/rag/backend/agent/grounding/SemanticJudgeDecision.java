package com.rag.backend.agent.grounding;

import java.util.Objects;

public record SemanticJudgeDecision(
        ClaimSupportStatus status,
        String reasonCode
) {
    public SemanticJudgeDecision {
        status = Objects.requireNonNull(status, "status");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
    }
}
