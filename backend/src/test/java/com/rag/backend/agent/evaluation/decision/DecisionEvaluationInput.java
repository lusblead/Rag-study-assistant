package com.rag.backend.agent.evaluation.decision;

import java.util.List;

/** Policy-visible input. It contains no truth label, review metadata, or grader rubric. */
public record DecisionEvaluationInput(
        String caseId,
        String originalQuestion,
        List<AnswerabilityDecisionCase.HistoryMessage> history,
        AnswerabilityDecisionCase.DecisionConstraints constraints,
        FrozenDecisionSnapshot retrievalSnapshot
) {
    public DecisionEvaluationInput {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
