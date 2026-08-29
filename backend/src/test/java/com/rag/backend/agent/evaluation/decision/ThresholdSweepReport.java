package com.rag.backend.agent.evaluation.decision;

import com.rag.backend.agent.evaluation.DatasetSplit;

import java.util.List;

/** Aggregate-only report. No query, chunk ID, content, path, or reviewer identity is emitted. */
public record ThresholdSweepReport(
        String schemaVersion,
        String runStatus,
        String datasetId,
        DatasetSplit split,
        int caseCount,
        String caseSha256,
        String snapshotSha256,
        String corpusSha256,
        String configurationSha256,
        String pipelineFingerprint,
        String scoreKind,
        String scoreDirection,
        String evaluatorVersion,
        boolean frozenSnapshotReplay,
        String predictorId,
        String confusionMatrixConvention,
        ThresholdSweepBaseline baseline,
        List<ThresholdSweepPoint> sweep
) {
    public ThresholdSweepReport {
        sweep = List.copyOf(sweep);
    }
}
