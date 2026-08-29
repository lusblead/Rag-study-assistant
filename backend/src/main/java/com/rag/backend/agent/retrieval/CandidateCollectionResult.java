package com.rag.backend.agent.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** 未融合的双源收集结果。 */
public record CandidateCollectionResult(
        List<CandidateBatch> batches,
        boolean degraded,
        List<CandidateSourceDiagnostic> diagnostics) {
    public CandidateCollectionResult {
        Objects.requireNonNull(batches, "batches");
        Objects.requireNonNull(diagnostics, "diagnostics");
        batches = List.copyOf(batches);
        diagnostics = List.copyOf(diagnostics);
    }

    public Optional<CandidateBatch> batch(CandidateSourceType source) {
        return batches.stream()
                .filter(batch -> batch.source() == source)
                .findFirst();
    }

    public Optional<CandidateSourceType> failedSource() {
        return diagnostics.stream()
                .filter(diagnostic -> !diagnostic.succeeded())
                .map(CandidateSourceDiagnostic::failedSource)
                .findFirst();
    }

    public Optional<CandidateSourceFailureType> failureType() {
        return diagnostics.stream()
                .filter(diagnostic -> !diagnostic.succeeded())
                .map(CandidateSourceDiagnostic::failureType)
                .findFirst();
    }

    public Map<CandidateSourceType, Long> sourceLatencyNanos() {
        return diagnostics.stream().collect(Collectors.toUnmodifiableMap(
                CandidateSourceDiagnostic::source,
                CandidateSourceDiagnostic::sourceLatencyNanos));
    }

    public Map<CandidateSourceType, Integer> candidateCount() {
        return diagnostics.stream().collect(Collectors.toUnmodifiableMap(
                CandidateSourceDiagnostic::source,
                CandidateSourceDiagnostic::candidateCount));
    }
}
