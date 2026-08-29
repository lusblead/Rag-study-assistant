package com.rag.backend.agent.evidence;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;

/** 生成前决策及其可审计、脱敏的证据。 */
public record EvidenceDecisionResult(
        AnswerabilityDecision decision,
        EvidenceDecisionReason reasonCode,
        List<Long> usableEvidenceIds,
        String missingInformation,
        EvidenceObservedSignals observedSignals,
        String policyVersion
) {
    public EvidenceDecisionResult {
        decision = Objects.requireNonNull(decision, "decision");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        usableEvidenceIds = List.copyOf(Objects.requireNonNull(
                usableEvidenceIds, "usableEvidenceIds"));
        observedSignals = Objects.requireNonNull(
                observedSignals, "observedSignals");
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion is required");
        }
        if (usableEvidenceIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(usableEvidenceIds).size()
                != usableEvidenceIds.size()) {
            throw new IllegalArgumentException(
                    "usableEvidenceIds must be unique and non-null");
        }
        if (decision == AnswerabilityDecision.ANSWER
                && usableEvidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "ANSWER requires at least one usable evidence ID");
        }
        if (decision != AnswerabilityDecision.ANSWER
                && !usableEvidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "only ANSWER may expose usable evidence IDs");
        }
        if (decision == AnswerabilityDecision.CLARIFY
                && (missingInformation == null
                || missingInformation.isBlank())) {
            throw new IllegalArgumentException(
                    "CLARIFY requires missingInformation");
        }
        if (decision != AnswerabilityDecision.CLARIFY
                && missingInformation != null
                && !missingInformation.isBlank()) {
            throw new IllegalArgumentException(
                    "only CLARIFY may expose missingInformation");
        }
    }
}
