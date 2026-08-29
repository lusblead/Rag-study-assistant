package com.rag.backend.agent.evaluation.decision;

import com.rag.backend.agent.evaluation.DatasetSplit;
import com.rag.backend.agent.evaluation.Severity;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Human-authored truth for one pre-generation decision.
 *
 * <p>{@link #expected} is grader-only. {@link #evaluationInput(FrozenDecisionSnapshot)}
 * deliberately excludes it so a policy adapter cannot read the gold decision.
 */
public record AnswerabilityDecisionCase(
        String schemaVersion,
        String caseId,
        DatasetSplit split,
        Severity severity,
        DecisionScenario scenario,
        CaseInput input,
        ExpectedDecision expected,
        HumanReview review,
        Set<String> tags,
        Provenance provenance
) {
    public AnswerabilityDecisionCase {
        tags = tags == null
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(tags));
    }

    public DecisionEvaluationInput evaluationInput(FrozenDecisionSnapshot snapshot) {
        return new DecisionEvaluationInput(
                caseId,
                input.question(),
                input.history(),
                input.constraints(),
                snapshot);
    }

    public record CaseInput(
            String question,
            List<HistoryMessage> history,
            DecisionConstraints constraints
    ) {
        public CaseInput {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    public record HistoryMessage(String role, String content) {
    }

    public record DecisionConstraints(boolean mustCite) {
    }

    public record ExpectedDecision(
            DecisionOutcome decision,
            String reasonCode,
            Set<String> requiredEvidenceIds,
            Set<String> confusingEvidenceIds,
            String clarificationTarget
    ) {
        public ExpectedDecision {
            requiredEvidenceIds = requiredEvidenceIds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(requiredEvidenceIds));
            confusingEvidenceIds = confusingEvidenceIds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(confusingEvidenceIds));
        }
    }

    public record HumanReview(
            String status,
            String reviewerType,
            String reviewerId,
            String reviewedAt,
            String notes
    ) {
    }

    public record Provenance(String sourceCaseKey, String sourceDatasetId) {
    }
}
