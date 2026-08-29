package com.rag.backend.agent.evaluation.decision;

import java.util.List;
import java.util.Map;

/** Validated formal cases and their one-to-one frozen retrieval snapshots. */
public record FormalDecisionDataset(
        DecisionDatasetManifest manifest,
        List<AnswerabilityDecisionCase> cases,
        Map<String, FrozenDecisionSnapshot> snapshotsByCaseId
) {
    public FormalDecisionDataset {
        cases = List.copyOf(cases);
        snapshotsByCaseId = Map.copyOf(snapshotsByCaseId);
    }
}
