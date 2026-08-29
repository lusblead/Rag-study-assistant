package com.rag.backend.agent.evaluation.decision;

/** The seven scenario families required by the Step 3.2 decision review. */
public enum DecisionScenario {
    NO_RETRIEVAL,
    LOW_RELEVANCE,
    SINGLE_EVIDENCE,
    CONSISTENT_MULTI_EVIDENCE,
    CONFLICTING_EVIDENCE,
    AMBIGUOUS_QUESTION,
    NO_ANSWER
}
