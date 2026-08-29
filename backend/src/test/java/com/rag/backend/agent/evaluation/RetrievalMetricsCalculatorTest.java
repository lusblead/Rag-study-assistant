package com.rag.backend.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsCalculatorTest {
    private static final double EPSILON = 1.0e-12;

    private final RetrievalMetricsCalculator calculator = new RetrievalMetricsCalculator();

    @Test
    void calculatesRecallMrrNdcgSourceAndEvidenceGroupCoverage() {
        RetrievalGroundTruth groundTruth = new RetrievalGroundTruth(
                "answerable-1",
                Answerability.ANSWERABLE,
                Set.of(1L, 2L),
                Set.of(3L),
                List.of(Set.of(1L, 3L), Set.of(2L, 4L)),
                Map.of(1L, 10L, 2L, 20L, 3L, 10L, 4L, 20L),
                Set.of(10L, 20L));

        CaseRetrievalMetrics result = calculator.evaluateCase(
                groundTruth, List.of(3L, 5L, 2L, 1L), 4);

        assertEquals(List.of(3L, 5L, 2L, 1L), result.rankedChunkIds());
        assertEquals(1.0, result.recallAtK(), EPSILON);
        assertEquals(0.5, result.precisionAtK(), EPSILON);
        assertEquals(1.0 / 3.0, result.reciprocalRank(), EPSILON);
        assertEquals(ndcg(List.of(0, 0, 1, 1), 2), result.ndcgAtK(), EPSILON);
        assertEquals(1.0, result.acceptableRecallAtK(), EPSILON);
        assertEquals(1.0, result.acceptableReciprocalRank(), EPSILON);
        assertEquals(ndcg(List.of(1, 0, 1, 1), 3), result.acceptableNdcgAtK(), EPSILON);
        assertEquals(1.0, result.sourceCoverageAtK(), EPSILON);
        assertEquals(1.0, result.requiredEvidenceGroupCoverageAtK(), EPSILON);
        assertEquals(0.0, result.unanswerableFalsePositive(), EPSILON);
    }

    @Test
    void definesEmptyDuplicateAlternativeAndZeroDenominatorBehavior() {
        Set<Long> strict = new LinkedHashSet<>(List.of(2L));
        Set<Long> acceptable = new LinkedHashSet<>(List.of(3L));
        List<Set<Long>> groups = new ArrayList<>();
        groups.add(new LinkedHashSet<>(List.of(2L, 3L)));
        groups.add(new LinkedHashSet<>(List.of(4L, 5L)));
        Map<Long, Long> mapping = new HashMap<>(Map.of(3L, 30L));
        RetrievalGroundTruth normalized = new RetrievalGroundTruth(
                "alternatives", Answerability.ANSWERABLE,
                strict, acceptable, groups, mapping, Set.of(30L));

        strict.add(99L);
        acceptable.add(98L);
        groups.get(0).add(97L);
        mapping.put(97L, 30L);

        CaseRetrievalMetrics alternative = calculator.evaluateCase(
                normalized, java.util.Arrays.asList(null, 3L, 3L, 4L, 2L), 3);
        assertEquals(Set.of(2L), normalized.relevantChunkIds());
        assertEquals(Set.of(2L, 3L), normalized.acceptableChunkIds());
        assertFalse(normalized.chunkIdToSourceId().containsKey(97L));
        assertThrows(UnsupportedOperationException.class,
                () -> normalized.acceptableChunkIds().add(100L));
        assertEquals(List.of(3L, 4L, 2L), alternative.rankedChunkIds());
        assertEquals(1.0, alternative.recallAtK(), EPSILON);
        assertEquals(1.0 / 3.0, alternative.reciprocalRank(), EPSILON);
        assertEquals(1.0, alternative.acceptableReciprocalRank(), EPSILON);
        assertEquals(1.0, alternative.sourceCoverageAtK(), EPSILON);
        assertEquals(1.0, alternative.requiredEvidenceGroupCoverageAtK(), EPSILON);

        RetrievalGroundTruth zeroDenominators = new RetrievalGroundTruth(
                "empty", Answerability.ANSWERABLE,
                Set.of(), Set.of(), List.of(), Map.of(), Set.of());
        CaseRetrievalMetrics empty = calculator.evaluateCase(zeroDenominators, null, 5);
        assertEquals(List.of(), empty.rankedChunkIds());
        assertAllMetricsAreFiniteZero(empty);
    }

    @Test
    void aggregatesAnswerableMetricsAndUnanswerableFalsePositivesSeparately() {
        RetrievalGroundTruth answerable = new RetrievalGroundTruth(
                "a", Answerability.ANSWERABLE,
                Set.of(1L), Set.of(2L), List.of(Set.of(1L, 2L)),
                Map.of(2L, 10L), Set.of(10L));
        RetrievalGroundTruth falsePositive = new RetrievalGroundTruth(
                "u1", Answerability.UNANSWERABLE,
                Set.of(9L), Set.of(9L), List.of(Set.of(9L)),
                Map.of(9L, 90L), Set.of(90L));
        RetrievalGroundTruth correctEmpty = new RetrievalGroundTruth(
                "u2", Answerability.UNANSWERABLE,
                Set.of(), Set.of(), List.of(), Map.of(), Set.of());

        CaseRetrievalMetrics answerableResult = calculator.evaluateCase(
                answerable, List.of(2L), 2);
        CaseRetrievalMetrics falsePositiveResult = calculator.evaluateCase(
                falsePositive, List.of(9L), 2);
        CaseRetrievalMetrics correctEmptyResult = calculator.evaluateCase(
                correctEmpty, List.of(), 2);

        assertAllMetricsAreFiniteZeroExceptFalsePositive(falsePositiveResult);
        assertEquals(1.0, falsePositiveResult.unanswerableFalsePositive(), EPSILON);

        RetrievalEvalReport report = calculator.summarizeGroundTruth(
                List.of(answerable, falsePositive, correctEmpty),
                List.of(answerableResult, falsePositiveResult, correctEmptyResult),
                2);
        assertEquals(1, report.answerableCases());
        assertEquals(2, report.unanswerableCases());
        assertEquals(answerableResult.recallAtK(), report.macroRecallAtK(), EPSILON);
        assertEquals(answerableResult.acceptableRecallAtK(),
                report.macroAcceptableRecallAtK(), EPSILON);
        assertEquals(0.5, report.unanswerableFalsePositiveRate(), EPSILON);
        assertEquals(0.5, report.emptyRetrievalAccuracy(), EPSILON);
        assertEquals(1.0,
                report.unanswerableFalsePositiveRate()
                        + report.emptyRetrievalAccuracy(), EPSILON);

        RetrievalEvalReport noAnswerable = calculator.summarizeGroundTruth(
                List.of(correctEmpty), List.of(correctEmptyResult), 2);
        assertEquals(0.0, noAnswerable.macroRecallAtK(), EPSILON);
        assertEquals(0.0, noAnswerable.macroAcceptableNdcgAtK(), EPSILON);

        RetrievalEvalReport emptyReport = calculator.summarizeGroundTruth(
                List.of(), List.of(), 2);
        assertEquals(0.0, emptyReport.emptyRetrievalAccuracy(), EPSILON);
        assertEquals(0.0, emptyReport.unanswerableFalsePositiveRate(), EPSILON);
        assertTrue(Double.isFinite(emptyReport.macroNdcgAtK()));
    }

    @Test
    void rejectsInvalidKSizeMismatchAndCaseOrderMismatch() {
        RetrievalGroundTruth first = groundTruth("first");
        RetrievalGroundTruth second = groundTruth("second");
        CaseRetrievalMetrics firstResult = calculator.evaluateCase(first, List.of(1L), 1);
        CaseRetrievalMetrics secondResult = calculator.evaluateCase(second, List.of(1L), 1);

        GoldenRagCase legacy = new GoldenRagCase(
                "legacy", 1L, "question", List.of(), Set.of(7L), List.of(),
                Answerability.ANSWERABLE, true, Set.of());
        CaseRetrievalMetrics legacyResult = calculator.evaluateCase(legacy, List.of(7L), 1);
        RetrievalEvalReport legacyReport = calculator.summarize(
                List.of(legacy), List.of(legacyResult), 1);
        assertEquals(1.0, legacyReport.macroRecallAtK(), EPSILON);
        assertEquals(1.0, legacyReport.macroAcceptableRecallAtK(), EPSILON);

        IllegalArgumentException invalidK = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.evaluateCase(first, List.of(1L), 0));
        assertEquals("k must be positive", invalidK.getMessage());
        assertEquals("k must be positive", assertThrows(
                IllegalArgumentException.class,
                () -> calculator.summarizeGroundTruth(List.of(), List.of(), -1)).getMessage());

        assertEquals("dataset/result size mismatch", assertThrows(
                IllegalArgumentException.class,
                () -> calculator.summarizeGroundTruth(
                        List.of(first), List.of(firstResult, secondResult), 1)).getMessage());
        assertEquals("case order mismatch at index 0", assertThrows(
                IllegalArgumentException.class,
                () -> calculator.summarizeGroundTruth(
                        List.of(first, second), List.of(secondResult, firstResult), 1)).getMessage());
    }

    private RetrievalGroundTruth groundTruth(String caseId) {
        return new RetrievalGroundTruth(
                caseId, Answerability.ANSWERABLE,
                Set.of(1L), Set.of(), List.of(), Map.of(), Set.of());
    }

    private void assertAllMetricsAreFiniteZero(CaseRetrievalMetrics metrics) {
        assertAllMetricsAreFiniteZeroExceptFalsePositive(metrics);
        assertEquals(0.0, metrics.unanswerableFalsePositive(), EPSILON);
    }

    private void assertAllMetricsAreFiniteZeroExceptFalsePositive(CaseRetrievalMetrics metrics) {
        List<Double> values = List.of(
                metrics.recallAtK(),
                metrics.precisionAtK(),
                metrics.reciprocalRank(),
                metrics.ndcgAtK(),
                metrics.acceptableRecallAtK(),
                metrics.acceptableReciprocalRank(),
                metrics.acceptableNdcgAtK(),
                metrics.sourceCoverageAtK(),
                metrics.requiredEvidenceGroupCoverageAtK());
        values.forEach(value -> {
            assertTrue(Double.isFinite(value));
            assertEquals(0.0, value, EPSILON);
        });
    }

    private double ndcg(List<Integer> gains, int idealHits) {
        double dcg = 0.0;
        for (int index = 0; index < gains.size(); index++) {
            if (gains.get(index) == 1) {
                dcg += 1.0 / log2(index + 2.0);
            }
        }
        double idcg = 0.0;
        for (int index = 0; index < idealHits; index++) {
            idcg += 1.0 / log2(index + 2.0);
        }
        return dcg / idcg;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }
}
