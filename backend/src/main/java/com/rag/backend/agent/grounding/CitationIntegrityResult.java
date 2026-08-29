package com.rag.backend.agent.grounding;

import java.util.List;
import java.util.Objects;

public record CitationIntegrityResult(
        boolean valid,
        double claimCoverage,
        List<AtomicClaim> claims,
        List<String> citedSourceIds,
        List<String> unknownSourceIds,
        List<CitationIntegrityFailure> failures,
        String validatorVersion
) {
    public CitationIntegrityResult {
        if (!Double.isFinite(claimCoverage)
                || claimCoverage < 0.0 || claimCoverage > 1.0) {
            throw new IllegalArgumentException(
                    "claimCoverage must be between 0 and 1");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        citedSourceIds = List.copyOf(Objects.requireNonNull(
                citedSourceIds, "citedSourceIds"));
        unknownSourceIds = List.copyOf(Objects.requireNonNull(
                unknownSourceIds, "unknownSourceIds"));
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (validatorVersion == null || validatorVersion.isBlank()) {
            throw new IllegalArgumentException("validatorVersion is required");
        }
        if (valid != failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "valid must match citation failures");
        }
    }
}
