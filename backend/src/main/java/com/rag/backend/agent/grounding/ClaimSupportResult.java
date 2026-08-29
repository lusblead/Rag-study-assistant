package com.rag.backend.agent.grounding;

import java.util.List;
import java.util.Objects;

public record ClaimSupportResult(
        List<ClaimSupportAssessment> assessments,
        String evaluatorVersion,
        String semanticJudgeCalibrationId
) {
    public ClaimSupportResult {
        assessments = List.copyOf(Objects.requireNonNull(
                assessments, "assessments"));
        if (evaluatorVersion == null || evaluatorVersion.isBlank()) {
            throw new IllegalArgumentException("evaluatorVersion is required");
        }
        if (semanticJudgeCalibrationId == null
                || semanticJudgeCalibrationId.isBlank()) {
            throw new IllegalArgumentException(
                    "semanticJudgeCalibrationId is required");
        }
    }

    public boolean allSupported() {
        return assessments.stream().allMatch(
                assessment -> assessment.status()
                        == ClaimSupportStatus.SUPPORTED);
    }

    public long count(ClaimSupportStatus status) {
        return assessments.stream()
                .filter(assessment -> assessment.status() == status)
                .count();
    }
}
