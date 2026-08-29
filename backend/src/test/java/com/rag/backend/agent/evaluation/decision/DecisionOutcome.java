package com.rag.backend.agent.evaluation.decision;

/** A pre-generation evidence decision. This is intentionally not retrieval answerability. */
public enum DecisionOutcome {
    ANSWER,
    CLARIFY,
    REFUSE
}
