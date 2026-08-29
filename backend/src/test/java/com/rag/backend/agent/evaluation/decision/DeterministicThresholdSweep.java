package com.rag.backend.agent.evaluation.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Replays one immutable snapshot set over every deterministic score boundary. */
public final class DeterministicThresholdSweep {
    private static final String REPORT_SCHEMA = "answerability-threshold-sweep-v1";

    private final DecisionMetricsCalculator metricsCalculator;
    private final DecisionQualityGate qualityGate;
    private final DeterministicThresholdGrid thresholdGrid;

    public DeterministicThresholdSweep() {
        this(new DecisionMetricsCalculator(),
                new DecisionQualityGate(),
                new DeterministicThresholdGrid());
    }

    DeterministicThresholdSweep(
            DecisionMetricsCalculator metricsCalculator,
            DecisionQualityGate qualityGate,
            DeterministicThresholdGrid thresholdGrid) {
        this.metricsCalculator = metricsCalculator;
        this.qualityGate = qualityGate;
        this.thresholdGrid = thresholdGrid;
    }

    public ThresholdSweepReport run(
            FormalDecisionDataset dataset,
            ThresholdDecisionPredictor predictor) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(predictor, "predictor");
        List<AnswerabilityDecisionCase> cases = dataset.cases();
        DecisionDatasetManifest manifest = dataset.manifest();
        if (!manifest.evaluatorVersion().equals(predictor.predictorId())) {
            throw new IllegalArgumentException(
                    "manifest evaluatorVersion does not match predictorId");
        }

        List<DecisionPrediction> baselinePredictions =
                metricsCalculator.allAnswerBaseline(cases);
        DecisionMetricsReport baselineMetrics = metricsCalculator.evaluate(
                cases, baselinePredictions);
        DecisionQualityGateResult baselineGate = qualityGate.evaluate(
                cases,
                baselinePredictions,
                baselineMetrics,
                baselineMetrics,
                manifest.qualityGates());

        List<Double> thresholds = thresholdGrid.fromSnapshots(
                dataset.snapshotsByCaseId().values());
        List<ThresholdSweepPoint> points = new ArrayList<>();
        for (double threshold : thresholds) {
            List<DecisionPrediction> predictions = cases.stream()
                    .map(item -> {
                        FrozenDecisionSnapshot snapshot =
                                dataset.snapshotsByCaseId().get(item.caseId());
                        return predictor.predict(item.evaluationInput(snapshot), threshold);
                    })
                    .toList();
            DecisionMetricsReport metrics = metricsCalculator.evaluate(cases, predictions);
            DecisionQualityGateResult gate = qualityGate.evaluate(
                    cases,
                    predictions,
                    metrics,
                    baselineMetrics,
                    manifest.qualityGates());
            points.add(new ThresholdSweepPoint(threshold, metrics, gate));
        }
        return new ThresholdSweepReport(
                REPORT_SCHEMA,
                "COMPLETED",
                manifest.datasetId(),
                manifest.split(),
                cases.size(),
                manifest.caseSha256(),
                manifest.snapshotSha256(),
                manifest.corpusSha256(),
                manifest.configurationSha256(),
                manifest.pipelineFingerprint(),
                manifest.scoreKind(),
                manifest.scoreDirection(),
                manifest.evaluatorVersion(),
                true,
                predictor.predictorId(),
                "truth rows; prediction columns; order ANSWER, CLARIFY, REFUSE",
                new ThresholdSweepBaseline(
                        "ALL_ANSWER",
                        baselineMetrics,
                        baselineGate),
                points);
    }
}
