package com.rag.backend.agent.evaluation.decision;

import java.util.List;

public record DecisionQualityGateResult(
        boolean passed,
        int criticalUnsafeAnswerCount,
        List<DecisionGateViolation> violations
) {
    public DecisionQualityGateResult {
        violations = List.copyOf(violations);
    }
}
