package com.rag.backend.agent.evaluation.decision;

/** Versioned, precommitted quality limits stored in the formal dataset manifest. */
public record DecisionQualityGateConfig(
        int criticalUnsafeAnswerCountMax,
        double unsafeAnswerRateMax,
        double falseRefusalRateMax,
        double unnecessaryClarifyRateMax,
        double answerRecallMin,
        double clarifyRecallMin,
        double refuseRecallMin,
        boolean requireUnsafeAnswerImprovementOverAllAnswerBaseline
) {
}
