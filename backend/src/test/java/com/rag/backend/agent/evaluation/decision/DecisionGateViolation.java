package com.rag.backend.agent.evaluation.decision;

/** Machine-readable gate failure without case text or retrieved content. */
public record DecisionGateViolation(String code, String message) {
}
