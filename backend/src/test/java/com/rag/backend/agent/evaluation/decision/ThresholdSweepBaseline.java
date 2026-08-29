package com.rag.backend.agent.evaluation.decision;

public record ThresholdSweepBaseline(
        String name,
        DecisionMetricsReport metrics,
        DecisionQualityGateResult gate
) {
}
