package com.rag.backend.agent.evaluation.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.evaluation.DatasetSplit;
import com.rag.backend.agent.evaluation.Severity;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DecisionMetricsCalculatorTest {
    private static final double EPSILON = 1.0e-12;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionMetricsCalculator calculator = new DecisionMetricsCalculator();

    @Test
    void computesTruthRowsPredictionColumnsAndNamedRiskRates() throws Exception {
        Fixture fixture = loadFixture();

        DecisionMetricsReport report = calculator.evaluate(
                fixture.cases(), fixture.predictions());
        DecisionConfusionMatrix matrix = report.confusionMatrix();

        assertEquals("truth", matrix.rowAxis());
        assertEquals("prediction", matrix.columnAxis());
        assertEquals(List.of(
                DecisionOutcome.ANSWER,
                DecisionOutcome.CLARIFY,
                DecisionOutcome.REFUSE), matrix.truthRowOrder());
        assertEquals(List.of(List.of(2, 1, 1), List.of(1, 2, 0), List.of(1, 1, 2)),
                matrix.counts());

        assertRate(report.unsafeAnswerRate(), 2, 7, 2.0 / 7.0);
        assertRate(report.falseRefusalRate(), 1, 4, 0.25);
        assertRate(report.unnecessaryClarifyRate(), 2, 8, 0.25);

        PerClassDecisionMetrics answer = report.perClass().get(DecisionOutcome.ANSWER);
        assertEquals(0.5, answer.precision().value(), EPSILON);
        assertEquals(0.5, answer.recall().value(), EPSILON);
        PerClassDecisionMetrics clarify = report.perClass().get(DecisionOutcome.CLARIFY);
        assertEquals(0.5, clarify.precision().value(), EPSILON);
        assertEquals(2.0 / 3.0, clarify.recall().value(), EPSILON);
        PerClassDecisionMetrics refuse = report.perClass().get(DecisionOutcome.REFUSE);
        assertEquals(2.0 / 3.0, refuse.precision().value(), EPSILON);
        assertEquals(0.5, refuse.recall().value(), EPSILON);

        assertEquals("N/A", report.calibration().status());
        assertNull(report.calibration().brierScore());
        assertNull(report.calibration().expectedCalibrationError());
    }

    @Test
    void allAnswerBaselineIsExplicitAndAlsoHasNoCalibration() throws Exception {
        Fixture fixture = loadFixture();
        List<DecisionPrediction> baseline = calculator.allAnswerBaseline(fixture.cases());

        DecisionMetricsReport report = calculator.evaluate(fixture.cases(), baseline);

        assertEquals(11, report.caseCount());
        assertRate(report.unsafeAnswerRate(), 7, 7, 1.0);
        assertRate(report.falseRefusalRate(), 0, 4, 0.0);
        assertRate(report.unnecessaryClarifyRate(), 0, 8, 0.0);
        assertEquals("N/A", report.calibration().status());
    }

    private Fixture loadFixture() throws Exception {
        List<AnswerabilityDecisionCase> cases = new ArrayList<>();
        List<DecisionPrediction> predictions = new ArrayList<>();
        try (InputStream input = getClass().getResourceAsStream(
                "/evaluation/decision/metrics-confusion-v1.jsonl");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode row = mapper.readTree(line);
                assertEquals("FIXTURE_ONLY", row.path("fixtureStatus").asText());
                String id = row.path("id").asText();
                DecisionOutcome truth = DecisionOutcome.valueOf(row.path("truth").asText());
                DecisionOutcome prediction =
                        DecisionOutcome.valueOf(row.path("prediction").asText());
                Severity severity = Severity.valueOf(row.path("severity").asText());
                cases.add(decisionCase(id, truth, severity));
                predictions.add(new DecisionPrediction(
                        id, prediction, null, "FIXTURE", "fixture-policy-v1"));
            }
        }
        return new Fixture(cases, predictions);
    }

    private AnswerabilityDecisionCase decisionCase(
            String id,
            DecisionOutcome truth,
            Severity severity) {
        Set<String> required = truth == DecisionOutcome.ANSWER
                ? Set.of("evidence-1") : Set.of();
        String clarificationTarget = truth == DecisionOutcome.CLARIFY
                ? "missing subject" : null;
        return new AnswerabilityDecisionCase(
                FormalDecisionDatasetLoader.CASE_SCHEMA,
                id,
                DatasetSplit.DEVELOPMENT,
                severity,
                truth == DecisionOutcome.CLARIFY
                        ? DecisionScenario.AMBIGUOUS_QUESTION
                        : truth == DecisionOutcome.REFUSE
                        ? DecisionScenario.NO_ANSWER
                        : DecisionScenario.SINGLE_EVIDENCE,
                new AnswerabilityDecisionCase.CaseInput(
                        "fixture question",
                        List.of(),
                        new AnswerabilityDecisionCase.DecisionConstraints(true)),
                new AnswerabilityDecisionCase.ExpectedDecision(
                        truth,
                        "FIXTURE_TRUTH",
                        required,
                        Set.of(),
                        clarificationTarget),
                new AnswerabilityDecisionCase.HumanReview(
                        "FIXTURE_ONLY", "SYNTHETIC", "fixture", null,
                        "arithmetic fixture; not formal evidence"),
                Set.of("fixture-only"),
                new AnswerabilityDecisionCase.Provenance("fixture", "fixture-only"));
    }

    private void assertRate(
            RateMetric metric,
            int numerator,
            int denominator,
            double expected) {
        assertEquals(numerator, metric.numerator());
        assertEquals(denominator, metric.denominator());
        assertEquals(expected, metric.value(), EPSILON);
    }

    private record Fixture(
            List<AnswerabilityDecisionCase> cases,
            List<DecisionPrediction> predictions) {
    }
}
