package com.rag.backend.agent.evaluation.decision;

/** A rate with its numerator and denominator preserved; value is null when undefined. */
public record RateMetric(int numerator, int denominator, Double value) {
    public static RateMetric of(int numerator, int denominator) {
        return new RateMetric(
                numerator,
                denominator,
                denominator == 0 ? null : numerator / (double) denominator);
    }
}
