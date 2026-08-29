package com.rag.backend.agent.evaluation.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.evaluation.DatasetSplit;
import com.rag.backend.agent.evaluation.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalDecisionDatasetLoaderTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loadsHashedDevelopmentDatasetWithExplicitHumanReviewAndAllScenarios()
            throws Exception {
        writeDataset("HUMAN", DatasetSplit.DEVELOPMENT);

        FormalDecisionDataset loaded =
                new FormalDecisionDatasetLoader(mapper).load(tempDir);

        assertEquals(8, loaded.cases().size());
        assertEquals(8, loaded.snapshotsByCaseId().size());
        assertFalse(loaded.snapshotsByCaseId().get("no-retrieval").candidates().iterator()
                .hasNext());
        assertEquals(DecisionOutcome.CLARIFY,
                loaded.cases().stream()
                        .filter(item -> item.caseId().equals("ambiguous-1"))
                        .findFirst()
                        .orElseThrow()
                        .expected()
                        .decision());
    }

    @Test
    void rejectsAiReviewEvenWhenStatusClaimsHumanConfirmed() throws Exception {
        writeDataset("AI", DatasetSplit.DEVELOPMENT);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new FormalDecisionDatasetLoader(mapper).load(tempDir));

        assertTrue(error.getMessage().contains("HUMAN_CONFIRMED human review"));
    }

    @Test
    void rejectsFrozenTestSplitBeforeThresholdSweep() throws Exception {
        writeDataset("HUMAN", DatasetSplit.FROZEN);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new FormalDecisionDatasetLoader(mapper).load(tempDir));

        assertTrue(error.getMessage().contains("only accepts DEVELOPMENT"));
    }

    @Test
    void rejectsCaseFileChangedAfterManifestWasWritten() throws Exception {
        writeDataset("HUMAN", DatasetSplit.DEVELOPMENT);
        Files.writeString(
                tempDir.resolve("dev.cases.jsonl"),
                System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new FormalDecisionDatasetLoader(mapper).load(tempDir));

        assertTrue(error.getMessage().contains("caseSha256 mismatch"));
    }

    @Test
    void rejectsNullQualityGateInsteadOfUsingAJavaPrimitiveDefault()
            throws Exception {
        writeDataset("HUMAN", DatasetSplit.DEVELOPMENT);
        Path manifestPath = tempDir.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) mapper.readTree(manifestPath.toFile());
        ((ObjectNode) manifest.get("qualityGates"))
                .putNull("unsafeAnswerRateMax");
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(manifestPath.toFile(), manifest);

        assertThrows(
                java.io.IOException.class,
                () -> new FormalDecisionDatasetLoader(mapper).load(tempDir));
    }

    @Test
    void rejectsQualityGatesThatWereNotExplicitlyPrecommitted()
            throws Exception {
        writeDataset("HUMAN", DatasetSplit.DEVELOPMENT);
        Path manifestPath = tempDir.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) mapper.readTree(manifestPath.toFile());
        manifest.put("qualityGateApproval", "UNAPPROVED");
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(manifestPath.toFile(), manifest);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new FormalDecisionDatasetLoader(mapper).load(tempDir));

        assertTrue(error.getMessage().contains("precommitted and approved"));
    }

    @Test
    void rejectsSnapshotCandidatesBeyondProductionFinalK() throws Exception {
        writeDataset("HUMAN", DatasetSplit.DEVELOPMENT);
        List<FrozenDecisionSnapshot> values = new ArrayList<>(snapshots());
        int index = 2;
        FrozenDecisionSnapshot source = values.get(index);
        List<FrozenDecisionSnapshot.SnapshotCandidate> candidates =
                new ArrayList<>(source.candidates());
        candidates.add(candidate("outside-final-k", 2, 0.8));
        values.set(index, new FrozenDecisionSnapshot(
                source.schemaVersion(),
                source.caseId(),
                source.snapshotId(),
                source.pipelineFingerprint(),
                source.scoreKind(),
                source.scoreDirection(),
                source.actualReranker(),
                source.candidateK(),
                source.finalK(),
                candidates));
        byte[] snapshotBytes = jsonLines(values);
        Files.write(tempDir.resolve("dev.snapshots.jsonl"), snapshotBytes);
        Path manifestPath = tempDir.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) mapper.readTree(
                manifestPath.toFile());
        manifest.put("snapshotSha256",
                FormalDecisionDatasetLoader.sha256(snapshotBytes));
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(manifestPath.toFile(), manifest);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new FormalDecisionDatasetLoader(mapper).load(tempDir));

        assertTrue(error.getMessage().contains("more rows than finalK"));
    }

    private void writeDataset(String reviewerType, DatasetSplit manifestSplit)
            throws Exception {
        List<AnswerabilityDecisionCase> cases = cases(reviewerType);
        List<FrozenDecisionSnapshot> snapshots = snapshots();
        byte[] caseBytes = jsonLines(cases);
        byte[] snapshotBytes = jsonLines(snapshots);
        Files.write(tempDir.resolve("dev.cases.jsonl"), caseBytes);
        Files.write(tempDir.resolve("dev.snapshots.jsonl"), snapshotBytes);

        EnumMap<DecisionOutcome, Integer> minimumCounts =
                new EnumMap<>(DecisionOutcome.class);
        minimumCounts.put(DecisionOutcome.ANSWER, 2);
        minimumCounts.put(DecisionOutcome.CLARIFY, 2);
        minimumCounts.put(DecisionOutcome.REFUSE, 4);
        DecisionDatasetManifest manifest = new DecisionDatasetManifest(
                FormalDecisionDatasetLoader.MANIFEST_SCHEMA,
                "formal-test-dataset",
                FormalDecisionDatasetLoader.FORMAL_DATASET_KIND,
                FormalDecisionDatasetLoader.PRIVATE_LOCAL,
                manifestSplit,
                "dev.cases.jsonl",
                FormalDecisionDatasetLoader.sha256(caseBytes),
                "dev.snapshots.jsonl",
                FormalDecisionDatasetLoader.sha256(snapshotBytes),
                "c".repeat(64),
                "d".repeat(64),
                cases.size(),
                minimumCounts,
                EnumSet.allOf(DecisionScenario.class),
                3,
                "sha256:pipeline-v1",
                "LEGACY_FINAL",
                FormalDecisionDatasetLoader.HIGHER_IS_BETTER,
                "loader-contract-evaluator-v1",
                "two-pass domain review",
                FormalDecisionDatasetLoader.QUALITY_GATES_APPROVED,
                new DecisionQualityGateConfig(
                        0, 0.0, 0.0, 0.0,
                        1.0, 1.0, 1.0, true));
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(tempDir.resolve("manifest.json").toFile(), manifest);
    }

    private List<AnswerabilityDecisionCase> cases(String reviewerType) {
        List<AnswerabilityDecisionCase> values = new ArrayList<>();
        values.add(item("no-retrieval", DecisionScenario.NO_RETRIEVAL,
                DecisionOutcome.REFUSE, Severity.CRITICAL, reviewerType,
                Set.of(), Set.of(), null));
        values.add(item("low-relevance", DecisionScenario.LOW_RELEVANCE,
                DecisionOutcome.REFUSE, Severity.CRITICAL, reviewerType,
                Set.of(), Set.of("low-relevance-evidence"), null));
        values.add(item("single-evidence", DecisionScenario.SINGLE_EVIDENCE,
                DecisionOutcome.ANSWER, Severity.HIGH, reviewerType,
                Set.of("single-evidence"), Set.of(), null));
        values.add(item("consistent-multi", DecisionScenario.CONSISTENT_MULTI_EVIDENCE,
                DecisionOutcome.ANSWER, Severity.HIGH, reviewerType,
                Set.of("multi-a", "multi-b"), Set.of(), null));
        values.add(item("conflict", DecisionScenario.CONFLICTING_EVIDENCE,
                DecisionOutcome.REFUSE, Severity.CRITICAL, reviewerType,
                Set.of(), Set.of("conflict-a", "conflict-b"), null));
        values.add(item("ambiguous-1", DecisionScenario.AMBIGUOUS_QUESTION,
                DecisionOutcome.CLARIFY, Severity.HIGH, reviewerType,
                Set.of(), Set.of(), "which component"));
        values.add(item("ambiguous-2", DecisionScenario.AMBIGUOUS_QUESTION,
                DecisionOutcome.CLARIFY, Severity.MEDIUM, reviewerType,
                Set.of(), Set.of(), "which time period"));
        values.add(item("no-answer", DecisionScenario.NO_ANSWER,
                DecisionOutcome.REFUSE, Severity.CRITICAL, reviewerType,
                Set.of(), Set.of("nearby-distractor"), null));
        return List.copyOf(values);
    }

    private AnswerabilityDecisionCase item(
            String id,
            DecisionScenario scenario,
            DecisionOutcome outcome,
            Severity severity,
            String reviewerType,
            Set<String> requiredEvidence,
            Set<String> confusingEvidence,
            String clarificationTarget) {
        return new AnswerabilityDecisionCase(
                FormalDecisionDatasetLoader.CASE_SCHEMA,
                id,
                DatasetSplit.DEVELOPMENT,
                severity,
                scenario,
                new AnswerabilityDecisionCase.CaseInput(
                        "synthetic loader contract question " + id,
                        List.of(),
                        new AnswerabilityDecisionCase.DecisionConstraints(true)),
                new AnswerabilityDecisionCase.ExpectedDecision(
                        outcome,
                        "REVIEWED_" + outcome,
                        requiredEvidence,
                        confusingEvidence,
                        clarificationTarget),
                new AnswerabilityDecisionCase.HumanReview(
                        FormalDecisionDatasetLoader.HUMAN_CONFIRMED,
                        reviewerType,
                        "test-reviewer",
                        "2026-08-12T10:00:00+08:00",
                        "test-only loader contract review"),
                Set.of("loader-contract"),
                new AnswerabilityDecisionCase.Provenance(
                        "test:" + id, "synthetic-loader-contract"));
    }

    private List<FrozenDecisionSnapshot> snapshots() {
        return List.of(
                snapshot("no-retrieval"),
                snapshot("low-relevance", candidate("low-relevance-evidence", 1, 0.1)),
                snapshot("single-evidence", candidate("single-evidence", 1, 0.9)),
                snapshot("consistent-multi",
                        candidate("multi-a", 1, 0.95), candidate("multi-b", 2, 0.9)),
                snapshot("conflict",
                        candidate("conflict-a", 1, 0.8), candidate("conflict-b", 2, 0.79)),
                snapshot("ambiguous-1", candidate("ambiguous-context", 1, 0.7)),
                snapshot("ambiguous-2", candidate("ambiguous-context-2", 1, 0.65)),
                snapshot("no-answer", candidate("nearby-distractor", 1, 0.6)));
    }

    private FrozenDecisionSnapshot snapshot(
            String caseId,
            FrozenDecisionSnapshot.SnapshotCandidate... candidates) {
        return new FrozenDecisionSnapshot(
                FormalDecisionDatasetLoader.SNAPSHOT_SCHEMA,
                caseId,
                "snapshot-" + caseId,
                "sha256:pipeline-v1",
                "LEGACY_FINAL",
                FormalDecisionDatasetLoader.HIGHER_IS_BETTER,
                "ORIGINAL",
                Math.max(2, candidates.length),
                Math.max(1, candidates.length),
                List.of(candidates));
    }

    private FrozenDecisionSnapshot.SnapshotCandidate candidate(
            String chunkId,
            int rank,
            double score) {
        return new FrozenDecisionSnapshot.SnapshotCandidate(
                chunkId,
                (long) rank,
                (long) rank,
                "source-" + chunkId,
                "title-" + chunkId,
                "synthetic policy-visible content " + chunkId,
                rank,
                rank,
                score);
    }

    private byte[] jsonLines(List<?> values) throws Exception {
        StringBuilder lines = new StringBuilder();
        for (Object value : values) {
            lines.append(mapper.writeValueAsString(value)).append('\n');
        }
        return lines.toString().getBytes(StandardCharsets.UTF_8);
    }
}
