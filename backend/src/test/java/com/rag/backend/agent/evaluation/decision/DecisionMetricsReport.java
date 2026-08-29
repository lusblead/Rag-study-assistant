package com.rag.backend.agent.evaluation.decision;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Aggregate-only three-class decision metrics. */
public record DecisionMetricsReport(
        int caseCount,
        DecisionConfusionMatrix confusionMatrix,
        Map<DecisionOutcome, PerClassDecisionMetrics> perClass,
        RateMetric unsafeAnswerRate,
        RateMetric falseRefusalRate,
        RateMetric unnecessaryClarifyRate,
        CalibrationReport calibration
) {
    public DecisionMetricsReport {
        perClass = Collections.unmodifiableMap(new EnumMap<>(perClass));
    }
}
