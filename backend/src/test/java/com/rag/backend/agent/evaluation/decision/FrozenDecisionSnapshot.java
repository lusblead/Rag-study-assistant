package com.rag.backend.agent.evaluation.decision;

import java.util.List;

/** Frozen FinalK policy-visible output replayed unchanged for every threshold arm. */
public record FrozenDecisionSnapshot(
        String schemaVersion,
        String caseId,
        String snapshotId,
        String pipelineFingerprint,
        String scoreKind,
        String scoreDirection,
        String actualReranker,
        int candidateK,
        int finalK,
        List<SnapshotCandidate> candidates
) {
    public FrozenDecisionSnapshot {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record SnapshotCandidate(
            String evidenceId,
            Long chunkId,
            Long documentId,
            String documentName,
            String title,
            String content,
            Integer sourcePage,
            int rank,
            double decisionScore
    ) {
    }
}
