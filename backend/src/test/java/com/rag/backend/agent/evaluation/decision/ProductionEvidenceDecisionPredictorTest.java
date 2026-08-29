package com.rag.backend.agent.evaluation.decision;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductionEvidenceDecisionPredictorTest {
    private final ProductionEvidenceDecisionPredictor predictor =
            new ProductionEvidenceDecisionPredictor();

    @Test
    void replaysThresholdThroughTheFullProductionRuleSet() {
        FrozenDecisionSnapshot snapshot = snapshot(
                "direct-support",
                candidate(
                        "direct-support", 101L, 201L, 1, 0.80,
                        "课程考试时长是 90 分钟。"));
        DecisionEvaluationInput input = input(
                "direct-support", "课程考试时长是多少？", snapshot);

        DecisionPrediction accepted = predictor.predict(input, 0.70);
        DecisionPrediction rejected = predictor.predict(input, 0.85);

        assertEquals(DecisionOutcome.ANSWER, accepted.decision());
        assertEquals("DIRECT_SUPPORT_OBSERVED", accepted.reasonCode());
        assertEquals(DecisionOutcome.REFUSE, rejected.decision());
        assertEquals("ALL_EVIDENCE_BELOW_CALIBRATED_THRESHOLD",
                rejected.reasonCode());
        assertNull(accepted.confidence());
        assertEquals(0.80, snapshot.candidates().get(0).decisionScore());
    }

    @Test
    void preservesAmbiguityConflictAndTraceabilityRulesAtTheSameThreshold() {
        DecisionPrediction ambiguous = predictor.predict(
                input(
                        "ambiguous",
                        "这个是什么？",
                        snapshot(
                                "ambiguous",
                                candidate(
                                        "ambiguous-context", 102L, 202L,
                                        1, 0.95, "课程使用形成性评价。"))),
                0.50);
        DecisionPrediction conflict = predictor.predict(
                input(
                        "conflict",
                        "课程考试时长是多少？",
                        snapshot(
                                "conflict",
                                candidate(
                                        "conflict-a", 103L, 203L,
                                        1, 0.95, "课程考试时长是 90 分钟。"),
                                candidate(
                                        "conflict-b", 104L, 204L,
                                        2, 0.90, "课程考试时长是 120 分钟。"))),
                0.50);
        DecisionPrediction untraceable = predictor.predict(
                input(
                        "untraceable",
                        "课程考试时长是多少？",
                        snapshot(
                                "untraceable",
                                candidate(
                                        "untraceable", 105L, null,
                                        1, 0.95, "课程考试时长是 90 分钟。"))),
                0.50);

        assertEquals(DecisionOutcome.CLARIFY, ambiguous.decision());
        assertEquals("MISSING_QUESTION_SCOPE", ambiguous.reasonCode());
        assertEquals(DecisionOutcome.CLARIFY, conflict.decision());
        assertEquals("CONFLICTING_EVIDENCE", conflict.reasonCode());
        assertEquals(DecisionOutcome.REFUSE, untraceable.decision());
        assertEquals("UNTRACEABLE_EVIDENCE", untraceable.reasonCode());
    }

    @Test
    void policyInputRecordCannotCarryTruthOrHumanReview() {
        Set<String> componentNames = Arrays.stream(
                        DecisionEvaluationInput.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                        "caseId",
                        "originalQuestion",
                        "history",
                        "constraints",
                        "retrievalSnapshot"),
                componentNames);
    }

    @Test
    void predictorNeverUsesCandidatesOutsideProductionFinalK() {
        FrozenDecisionSnapshot source = snapshot(
                "final-k-boundary",
                candidate(
                        "distractor", 106L, 206L,
                        1, 0.95, "课程教材使用电子版。"),
                candidate(
                        "outside-final-k", 107L, 207L,
                        2, 0.90, "课程考试时长是 90 分钟。"));
        FrozenDecisionSnapshot finalKOne = new FrozenDecisionSnapshot(
                source.schemaVersion(),
                source.caseId(),
                source.snapshotId(),
                source.pipelineFingerprint(),
                source.scoreKind(),
                source.scoreDirection(),
                source.actualReranker(),
                2,
                1,
                source.candidates());

        DecisionPrediction prediction = predictor.predict(
                input(
                        "final-k-boundary",
                        "课程考试时长是多少？",
                        finalKOne),
                0.50);

        assertEquals(DecisionOutcome.REFUSE, prediction.decision());
    }

    private DecisionEvaluationInput input(
            String caseId,
            String question,
            FrozenDecisionSnapshot snapshot) {
        return new DecisionEvaluationInput(
                caseId,
                question,
                List.of(),
                new AnswerabilityDecisionCase.DecisionConstraints(true),
                snapshot);
    }

    private FrozenDecisionSnapshot snapshot(
            String caseId,
            FrozenDecisionSnapshot.SnapshotCandidate... candidates) {
        return new FrozenDecisionSnapshot(
                FormalDecisionDatasetLoader.SNAPSHOT_SCHEMA,
                caseId,
                "snapshot-" + caseId,
                "sha256:production-adapter-fixture",
                "LOCAL_RERANK",
                FormalDecisionDatasetLoader.HIGHER_IS_BETTER,
                "LOCAL",
                Math.max(1, candidates.length),
                Math.max(1, candidates.length),
                List.of(candidates));
    }

    private FrozenDecisionSnapshot.SnapshotCandidate candidate(
            String evidenceId,
            Long chunkId,
            Long documentId,
            int rank,
            double score,
            String content) {
        return new FrozenDecisionSnapshot.SnapshotCandidate(
                evidenceId,
                chunkId,
                documentId,
                documentId == null ? null : "private-source-" + documentId,
                documentId == null ? null : "private-title-" + documentId,
                content,
                null,
                rank,
                score);
    }
}
