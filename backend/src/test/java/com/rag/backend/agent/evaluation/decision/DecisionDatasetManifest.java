package com.rag.backend.agent.evaluation.decision;

import com.rag.backend.agent.evaluation.DatasetSplit;

import java.util.Map;
import java.util.Set;

/** Manifest for a private, formally human-reviewed Step 3.2 dataset. */
public record DecisionDatasetManifest(
        String schemaVersion,
        String datasetId,
        String datasetKind,
        String dataClassification,
        DatasetSplit split,
        String caseFile,
        String caseSha256,
        String snapshotFile,
        String snapshotSha256,
        String corpusSha256,
        String configurationSha256,
        int expectedCaseCount,
        Map<DecisionOutcome, Integer> minimumClassCounts,
        Set<DecisionScenario> requiredScenarios,
        int minimumNonEmptyRefuseCases,
        String pipelineFingerprint,
        String scoreKind,
        String scoreDirection,
        String evaluatorVersion,
        String reviewProtocol,
        String qualityGateApproval,
        DecisionQualityGateConfig qualityGates
) {
    public DecisionDatasetManifest {
        minimumClassCounts = minimumClassCounts == null
                ? Map.of()
                : Map.copyOf(minimumClassCounts);
        requiredScenarios = requiredScenarios == null
                ? Set.of()
                : Set.copyOf(requiredScenarios);
    }
}
