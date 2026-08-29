package com.rag.backend.agent.evaluation.decision;

/** Precision uses predicted-class count; recall uses truth-class count. */
public record PerClassDecisionMetrics(
        int truePositive,
        int predictedCount,
        int truthCount,
        RateMetric precision,
        RateMetric recall
) {
}
