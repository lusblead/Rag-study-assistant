package com.rag.backend.agent.evaluation.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.evaluation.DatasetSplit;
import com.rag.backend.agent.evaluation.Severity;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicThresholdSweepTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void buildsStableBoundariesAndReplaysTheSameSnapshotsForEveryArm() throws Exception {
        FormalDecisionDataset dataset = dataset();
        DeterministicThresholdSweep sweep = new DeterministicThresholdSweep();

        ThresholdSweepReport first = sweep.run(dataset, new TestPredictor());
        ThresholdSweepReport second = sweep.run(dataset, new TestPredictor());

        assertEquals(4, first.sweep().size());
        assertTrue(first.sweep().get(0).answerThreshold() < 0.2);
        assertEquals(0.4, first.sweep().get(1).answerThreshold(), EPSILON);
        assertEquals(0.75, first.sweep().get(2).answerThreshold(), EPSILON);
        assertTrue(first.sweep().get(3).answerThreshold() > 0.9);
        assertTrue(first.frozenSnapshotReplay());
        assertEquals("truth rows; prediction columns; order ANSWER, CLARIFY, REFUSE",
                first.confusionMatrixConvention());
        assertEquals(1.0, first.baseline().metrics().unsafeAnswerRate().value(), EPSILON);
        assertEquals("N/A", first.baseline().metrics().calibration().status());
        assertTrue(first.sweep().get(1).gate().passed());
        assertFalse(first.sweep().get(0).gate().passed());

        ObjectMapper mapper = new ObjectMapper();
        String firstJson = mapper.writeValueAsString(first);
        String secondJson = mapper.writeValueAsString(second);
        assertEquals(firstJson, secondJson);
        assertFalse(firstJson.contains("answer question"));
        assertFalse(firstJson.contains("answer-evidence"));
        assertFalse(firstJson.contains("reviewer"));
    }

    @Test
    void rejectsPredictorVersionDriftFromTheFrozenManifest() {
        ThresholdDecisionPredictor mismatched = new ThresholdDecisionPredictor() {
            @Override
            public DecisionPrediction predict(
                    DecisionEvaluationInput input,
                    double answerThreshold) {
                return new TestPredictor().predict(input, answerThreshold);
            }

            @Override
            public String predictorId() {
                return "different-predictor-version";
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new DeterministicThresholdSweep().run(
                        dataset(), mismatched));

        assertTrue(error.getMessage().contains("evaluatorVersion"));
    }

    private FormalDecisionDataset dataset() {
        List<AnswerabilityDecisionCase> cases = List.of(
                decisionCase("answer", "answer question", DecisionOutcome.ANSWER,
                        DecisionScenario.SINGLE_EVIDENCE, Set.of("answer-evidence"), null),
                decisionCase("clarify", "ambiguous question", DecisionOutcome.CLARIFY,
                        DecisionScenario.AMBIGUOUS_QUESTION, Set.of(), "which subject"),
                decisionCase("refuse", "unsupported question", DecisionOutcome.REFUSE,
                        DecisionScenario.NO_ANSWER, Set.of(), null));
        Map<String, FrozenDecisionSnapshot> snapshots = Map.of(
                "answer", snapshot("answer", "answer-evidence", 0.9),
                "clarify", snapshot("clarify", "ambiguous-context", 0.6),
                "refuse", snapshot("refuse", "unsupported-distractor", 0.2));

        EnumMap<DecisionOutcome, Integer> minimum = new EnumMap<>(DecisionOutcome.class);
        minimum.put(DecisionOutcome.ANSWER, 1);
        minimum.put(DecisionOutcome.CLARIFY, 1);
        minimum.put(DecisionOutcome.REFUSE, 1);
        DecisionDatasetManifest manifest = new DecisionDatasetManifest(
                FormalDecisionDatasetLoader.MANIFEST_SCHEMA,
                "threshold-fixture",
                FormalDecisionDatasetLoader.FORMAL_DATASET_KIND,
                FormalDecisionDatasetLoader.PRIVATE_LOCAL,
                DatasetSplit.DEVELOPMENT,
                "unused.cases.jsonl",
                "a".repeat(64),
                "unused.snapshots.jsonl",
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                3,
                minimum,
                EnumSet.allOf(DecisionScenario.class),
                1,
                "sha256:threshold-fixture",
                "LEGACY_FINAL",
                FormalDecisionDatasetLoader.HIGHER_IS_BETTER,
                "fixture-threshold-predictor-v1",
                "fixture-only",
                FormalDecisionDatasetLoader.QUALITY_GATES_APPROVED,
                new DecisionQualityGateConfig(
                        0, 0.0, 0.0, 0.0,
                        1.0, 1.0, 1.0, true));
        return new FormalDecisionDataset(manifest, cases, snapshots);
    }

    private AnswerabilityDecisionCase decisionCase(
            String id,
            String question,
            DecisionOutcome outcome,
            DecisionScenario scenario,
            Set<String> evidence,
            String clarificationTarget) {
        return new AnswerabilityDecisionCase(
                FormalDecisionDatasetLoader.CASE_SCHEMA,
                id,
                DatasetSplit.DEVELOPMENT,
                Severity.CRITICAL,
                scenario,
                new AnswerabilityDecisionCase.CaseInput(
                        question, List.of(),
                        new AnswerabilityDecisionCase.DecisionConstraints(true)),
                new AnswerabilityDecisionCase.ExpectedDecision(
                        outcome, "FIXTURE", evidence, Set.of(), clarificationTarget),
                new AnswerabilityDecisionCase.HumanReview(
                        "FIXTURE_ONLY", "SYNTHETIC", "fixture", null,
                        "not formal evidence"),
                Set.of("fixture-only"),
                new AnswerabilityDecisionCase.Provenance("fixture:" + id, "fixture"));
    }

    private FrozenDecisionSnapshot snapshot(String caseId, String chunkId, double score) {
        return new FrozenDecisionSnapshot(
                FormalDecisionDatasetLoader.SNAPSHOT_SCHEMA,
                caseId,
                "snapshot-" + caseId,
                "sha256:threshold-fixture",
                "LEGACY_FINAL",
                FormalDecisionDatasetLoader.HIGHER_IS_BETTER,
                "ORIGINAL",
                1,
                1,
                List.of(new FrozenDecisionSnapshot.SnapshotCandidate(
                        chunkId,
                        1L,
                        1L,
                        "source-" + caseId,
                        "title-" + caseId,
                        "synthetic policy-visible content " + caseId,
                        1,
                        1,
                        score)));
    }

    public static final class TestPredictor implements ThresholdDecisionPredictor {
        @Override
        public DecisionPrediction predict(
                DecisionEvaluationInput input,
                double answerThreshold) {
            DecisionOutcome outcome;
            if (input.originalQuestion().contains("ambiguous")) {
                outcome = DecisionOutcome.CLARIFY;
            } else {
                double score = input.retrievalSnapshot().candidates().isEmpty()
                        ? Double.NEGATIVE_INFINITY
                        : input.retrievalSnapshot().candidates().get(0).decisionScore();
                outcome = score >= answerThreshold
                        ? DecisionOutcome.ANSWER
                        : DecisionOutcome.REFUSE;
            }
            return new DecisionPrediction(
                    input.caseId(), outcome, null, "FIXTURE", "fixture-policy-v1");
        }

        @Override
        public String predictorId() {
            return "fixture-threshold-predictor-v1";
        }
    }
}
