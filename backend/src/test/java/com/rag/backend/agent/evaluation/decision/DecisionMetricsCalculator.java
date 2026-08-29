package com.rag.backend.agent.evaluation.decision;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic aggregate calculator for ANSWER / CLARIFY / REFUSE decisions. */
public final class DecisionMetricsCalculator {
    private static final int CALIBRATION_BIN_COUNT = 10;

    public DecisionMetricsReport evaluate(
            List<AnswerabilityDecisionCase> cases,
            List<DecisionPrediction> predictions) {
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(predictions, "predictions");
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("decision cases must not be empty");
        }

        Map<String, DecisionPrediction> predictionByCaseId = new HashMap<>();
        for (DecisionPrediction prediction : predictions) {
            validatePrediction(prediction);
            if (predictionByCaseId.putIfAbsent(prediction.caseId(), prediction) != null) {
                throw new IllegalArgumentException(
                        "duplicate decision prediction caseId=" + prediction.caseId());
            }
        }
        if (predictionByCaseId.size() != cases.size()) {
            throw new IllegalArgumentException("decision case/prediction count mismatch");
        }

        int size = DecisionOutcome.values().length;
        int[][] counts = new int[size][size];
        List<PredictionJudgment> judgments = new ArrayList<>();
        for (AnswerabilityDecisionCase item : cases) {
            DecisionPrediction prediction = predictionByCaseId.remove(item.caseId());
            if (prediction == null) {
                throw new IllegalArgumentException(
                        "missing decision prediction for caseId=" + item.caseId());
            }
            DecisionOutcome truth = item.expected().decision();
            counts[truth.ordinal()][prediction.decision().ordinal()]++;
            judgments.add(new PredictionJudgment(
                    truth == prediction.decision(), prediction.confidence()));
        }
        if (!predictionByCaseId.isEmpty()) {
            throw new IllegalArgumentException(
                    "predictions contain unknown case IDs=" + predictionByCaseId.keySet());
        }

        DecisionConfusionMatrix matrix = matrix(counts);
        EnumMap<DecisionOutcome, PerClassDecisionMetrics> perClass =
                new EnumMap<>(DecisionOutcome.class);
        for (DecisionOutcome outcome : DecisionOutcome.values()) {
            int truthCount = rowSum(counts, outcome.ordinal());
            int predictedCount = columnSum(counts, outcome.ordinal());
            int truePositive = counts[outcome.ordinal()][outcome.ordinal()];
            perClass.put(outcome, new PerClassDecisionMetrics(
                    truePositive,
                    predictedCount,
                    truthCount,
                    RateMetric.of(truePositive, predictedCount),
                    RateMetric.of(truePositive, truthCount)));
        }

        int clarifyAsAnswer = counts[DecisionOutcome.CLARIFY.ordinal()]
                [DecisionOutcome.ANSWER.ordinal()];
        int refuseAsAnswer = counts[DecisionOutcome.REFUSE.ordinal()]
                [DecisionOutcome.ANSWER.ordinal()];
        int nonAnswerTruth = rowSum(counts, DecisionOutcome.CLARIFY.ordinal())
                + rowSum(counts, DecisionOutcome.REFUSE.ordinal());

        int answerAsRefuse = counts[DecisionOutcome.ANSWER.ordinal()]
                [DecisionOutcome.REFUSE.ordinal()];
        int answerTruth = rowSum(counts, DecisionOutcome.ANSWER.ordinal());

        int answerAsClarify = counts[DecisionOutcome.ANSWER.ordinal()]
                [DecisionOutcome.CLARIFY.ordinal()];
        int refuseAsClarify = counts[DecisionOutcome.REFUSE.ordinal()]
                [DecisionOutcome.CLARIFY.ordinal()];
        int answerOrRefuseTruth = answerTruth
                + rowSum(counts, DecisionOutcome.REFUSE.ordinal());

        return new DecisionMetricsReport(
                cases.size(),
                matrix,
                perClass,
                RateMetric.of(clarifyAsAnswer + refuseAsAnswer, nonAnswerTruth),
                RateMetric.of(answerAsRefuse, answerTruth),
                RateMetric.of(answerAsClarify + refuseAsClarify, answerOrRefuseTruth),
                calibration(judgments));
    }

    public List<DecisionPrediction> allAnswerBaseline(
            List<AnswerabilityDecisionCase> cases) {
        return cases.stream()
                .map(item -> new DecisionPrediction(
                        item.caseId(),
                        DecisionOutcome.ANSWER,
                        null,
                        "BASELINE_ALL_ANSWER",
                        "baseline-all-answer-v1"))
                .toList();
    }

    private DecisionConfusionMatrix matrix(int[][] counts) {
        List<List<Integer>> rows = new ArrayList<>();
        for (int[] count : counts) {
            List<Integer> row = new ArrayList<>();
            for (int value : count) {
                row.add(value);
            }
            rows.add(List.copyOf(row));
        }
        List<DecisionOutcome> order = List.of(DecisionOutcome.values());
        return new DecisionConfusionMatrix(
                "truth",
                order,
                "prediction",
                order,
                rows);
    }

    private CalibrationReport calibration(List<PredictionJudgment> judgments) {
        long confidenceCount = judgments.stream()
                .filter(item -> item.confidence() != null)
                .count();
        if (confidenceCount == 0) {
            return CalibrationReport.notApplicable(judgments.size());
        }
        if (confidenceCount != judgments.size()) {
            throw new IllegalArgumentException(
                    "confidence must be provided for all predictions or none");
        }

        double brier = judgments.stream()
                .mapToDouble(item -> {
                    double observed = item.correct() ? 1.0 : 0.0;
                    double delta = item.confidence() - observed;
                    return delta * delta;
                })
                .average()
                .orElseThrow();
        double ece = 0.0;
        for (int bin = 0; bin < CALIBRATION_BIN_COUNT; bin++) {
            int currentBin = bin;
            double lower = currentBin / (double) CALIBRATION_BIN_COUNT;
            double upper = (currentBin + 1) / (double) CALIBRATION_BIN_COUNT;
            List<PredictionJudgment> members = judgments.stream()
                    .filter(item -> item.confidence() >= lower
                            && (currentBin == CALIBRATION_BIN_COUNT - 1
                            ? item.confidence() <= upper
                            : item.confidence() < upper))
                    .toList();
            if (members.isEmpty()) {
                continue;
            }
            double meanConfidence = members.stream()
                    .mapToDouble(PredictionJudgment::confidence)
                    .average()
                    .orElseThrow();
            double accuracy = members.stream()
                    .filter(PredictionJudgment::correct)
                    .count() / (double) members.size();
            ece += members.size() / (double) judgments.size()
                    * Math.abs(meanConfidence - accuracy);
        }
        return new CalibrationReport(
                "COMPUTED",
                judgments.size(),
                brier,
                ece,
                CALIBRATION_BIN_COUNT,
                null);
    }

    private void validatePrediction(DecisionPrediction prediction) {
        if (prediction == null
                || prediction.caseId() == null
                || prediction.caseId().isBlank()
                || prediction.decision() == null) {
            throw new IllegalArgumentException(
                    "decision prediction needs caseId and decision");
        }
        if (prediction.confidence() != null
                && (!Double.isFinite(prediction.confidence())
                || prediction.confidence() < 0.0
                || prediction.confidence() > 1.0)) {
            throw new IllegalArgumentException(
                    "decision confidence must be finite and in [0, 1]");
        }
    }

    private int rowSum(int[][] counts, int row) {
        int total = 0;
        for (int value : counts[row]) {
            total += value;
        }
        return total;
    }

    private int columnSum(int[][] counts, int column) {
        int total = 0;
        for (int[] row : counts) {
            total += row[column];
        }
        return total;
    }

    private record PredictionJudgment(boolean correct, Double confidence) {
    }
}
