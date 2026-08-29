package com.rag.backend.agent.evaluation.decision;

/** One policy prediction; confidence is optional and means probability the decision is correct. */
public record DecisionPrediction(
        String caseId,
        DecisionOutcome decision,
        Double confidence,
        String reasonCode,
        String policyVersion
) {
}
