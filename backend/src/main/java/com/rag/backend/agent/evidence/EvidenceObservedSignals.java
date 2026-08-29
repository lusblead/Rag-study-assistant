package com.rag.backend.agent.evidence;

import com.rag.backend.agent.retrieval.RetrievalDiagnostics;

/**
 * 决策使用的脱敏信号。directLexicalCoverage 是规则特征，不是概率或置信度。
 */
public record EvidenceObservedSignals(
        int retrievedCount,
        int deduplicatedCount,
        int traceableCount,
        int eligibleCount,
        double directLexicalCoverage,
        boolean directAnswerShapeObserved,
        boolean ambiguousQuestion,
        boolean asksAboutConflict,
        boolean conflictDetected,
        boolean thresholdConfigured,
        String thresholdScoreKind,
        String thresholdCalibrationId,
        Double appliedThreshold,
        boolean retrievalDegraded,
        RetrievalDiagnostics.EmptyReason retrievalEmptyReason
) {
    public EvidenceObservedSignals {
        if (retrievedCount < 0 || deduplicatedCount < 0
                || traceableCount < 0 || eligibleCount < 0) {
            throw new IllegalArgumentException("signal counts must be >= 0");
        }
        if (!Double.isFinite(directLexicalCoverage)
                || directLexicalCoverage < 0.0
                || directLexicalCoverage > 1.0) {
            throw new IllegalArgumentException(
                    "directLexicalCoverage must be within [0,1]");
        }
        if (appliedThreshold != null && !Double.isFinite(appliedThreshold)) {
            throw new IllegalArgumentException(
                    "appliedThreshold must be finite or null");
        }
    }
}
