package com.rag.backend.agent.evaluation.decision;

public record ThresholdSweepPoint(
        double answerThreshold,
        DecisionMetricsReport metrics,
        DecisionQualityGateResult gate
) {
}
