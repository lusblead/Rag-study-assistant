package com.rag.backend.agent.grounding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 同步/SSE 共用的脱敏生成后校验诊断。 */
public record GroundingDiagnostics(
        GroundingExecutionStatus status,
        int generationAttempts,
        Boolean citationValid,
        Double citationCoverage,
        int supportedClaims,
        int unsupportedClaims,
        int contradictedClaims,
        int uncertainClaims,
        String failureReason,
        String validatorVersion,
        String semanticJudgeCalibrationId,
        List<String> sourceIds
) {
    public GroundingDiagnostics {
        status = Objects.requireNonNull(status, "status");
        if (generationAttempts < 0 || generationAttempts > 2) {
            throw new IllegalArgumentException(
                    "generationAttempts must be between 0 and 2");
        }
        if (citationCoverage != null
                && (!Double.isFinite(citationCoverage)
                || citationCoverage < 0.0 || citationCoverage > 1.0)) {
            throw new IllegalArgumentException(
                    "citationCoverage must be between 0 and 1");
        }
        if (supportedClaims < 0 || unsupportedClaims < 0
                || contradictedClaims < 0 || uncertainClaims < 0) {
            throw new IllegalArgumentException(
                    "claim counts must be non-negative");
        }
        sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
    }

    public static GroundingDiagnostics notApplicable() {
        return new GroundingDiagnostics(
                GroundingExecutionStatus.NOT_APPLICABLE,
                0, null, null,
                0, 0, 0, 0,
                null, null, null, List.of());
    }

    public static GroundingDiagnostics disabled(CitationCatalog catalog) {
        return new GroundingDiagnostics(
                GroundingExecutionStatus.DISABLED,
                1, null, null,
                0, 0, 0, 0,
                "GROUNDING_DISABLED", null,
                UncalibratedSemanticClaimJudge.CALIBRATION_ID,
                catalog.sourceIds());
    }

    public static GroundingDiagnostics from(
            GroundingExecutionStatus status,
            int attempts,
            GroundingValidationResult validation,
            CitationCatalog catalog) {
        CitationIntegrityResult citation = validation.citationIntegrity();
        ClaimSupportResult claims = validation.claimSupport();
        String judgeId = claims == null
                ? UncalibratedSemanticClaimJudge.CALIBRATION_ID
                : claims.semanticJudgeCalibrationId();
        return new GroundingDiagnostics(
                status,
                attempts,
                citation.valid(),
                citation.claimCoverage(),
                count(claims, ClaimSupportStatus.SUPPORTED),
                count(claims, ClaimSupportStatus.UNSUPPORTED),
                count(claims, ClaimSupportStatus.CONTRADICTED),
                count(claims, ClaimSupportStatus.UNCERTAIN),
                failureReason(validation),
                citation.validatorVersion()
                        + (claims == null ? "" : "+" + claims.evaluatorVersion()),
                judgeId,
                catalog.sourceIds());
    }

    private static int count(
            ClaimSupportResult result, ClaimSupportStatus status) {
        return result == null ? 0 : Math.toIntExact(result.count(status));
    }

    private static String failureReason(
            GroundingValidationResult validation) {
        if (validation.accepted()) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        reasons.addAll(validation.citationIntegrity().failures().stream()
                .map(failure -> failure.reason().name())
                .toList());
        if (validation.claimSupport() != null) {
            reasons.addAll(validation.claimSupport().assessments().stream()
                    .filter(assessment -> assessment.status()
                            != ClaimSupportStatus.SUPPORTED)
                    .map(ClaimSupportAssessment::reasonCode)
                    .toList());
        }
        return reasons.stream().distinct().collect(Collectors.joining(","));
    }
}
