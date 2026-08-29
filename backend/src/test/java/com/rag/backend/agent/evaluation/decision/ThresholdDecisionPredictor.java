package com.rag.backend.agent.evaluation.decision;

/**
 * Adapter seam for a production policy or a replayed predictor.
 * Implementations receive policy-visible input only, never the expected decision.
 */
@FunctionalInterface
public interface ThresholdDecisionPredictor {
    DecisionPrediction predict(DecisionEvaluationInput input, double answerThreshold);

    default String predictorId() {
        return getClass().getName();
    }
}
