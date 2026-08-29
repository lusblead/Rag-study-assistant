package com.rag.backend.agent.evaluation.decision;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.evaluation.DatasetSplit;
import com.rag.backend.agent.evidence.EvidenceScoreKind;
import com.rag.backend.agent.rerank.RerankExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail-closed loader for a formal, human-reviewed decision dataset. */
public final class FormalDecisionDatasetLoader {
    public static final String MANIFEST_SCHEMA = "answerability-decision-manifest-v1";
    public static final String CASE_SCHEMA = "answerability-decision-v1";
    public static final String SNAPSHOT_SCHEMA = "frozen-decision-snapshot-v1";
    public static final String FORMAL_DATASET_KIND = "FORMAL_HUMAN_REVIEWED";
    public static final String PRIVATE_LOCAL = "PRIVATE_LOCAL";
    public static final String HUMAN_CONFIRMED = "HUMAN_CONFIRMED";
    public static final String HUMAN_REVIEWER = "HUMAN";
    public static final String HIGHER_IS_BETTER = "HIGHER_IS_BETTER";
    public static final String QUALITY_GATES_APPROVED =
            "PRECOMMITTED_AND_APPROVED";

    private final ObjectMapper mapper;

    public FormalDecisionDatasetLoader(ObjectMapper mapper) {
        this.mapper = mapper.copy().configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                true).configure(
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                true);
    }

    public FormalDecisionDataset load(Path datasetRoot) throws IOException {
        Path root = datasetRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("decision dataset directory does not exist: " + root);
        }
        Path manifestPath = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException("decision dataset manifest is missing: " + manifestPath);
        }
        DecisionDatasetManifest manifest = mapper.readValue(
                manifestPath.toFile(), DecisionDatasetManifest.class);
        validateManifest(manifest);

        Path casePath = resolveDatasetFile(root, manifest.caseFile(), "caseFile");
        Path snapshotPath = resolveDatasetFile(root, manifest.snapshotFile(), "snapshotFile");
        requireHash(casePath, manifest.caseSha256(), "caseSha256");
        requireHash(snapshotPath, manifest.snapshotSha256(), "snapshotSha256");

        List<AnswerabilityDecisionCase> cases = readJsonLines(
                casePath, AnswerabilityDecisionCase.class);
        List<FrozenDecisionSnapshot> snapshots = readJsonLines(
                snapshotPath, FrozenDecisionSnapshot.class);
        return validateDataset(manifest, cases, snapshots);
    }

    private FormalDecisionDataset validateDataset(
            DecisionDatasetManifest manifest,
            List<AnswerabilityDecisionCase> cases,
            List<FrozenDecisionSnapshot> snapshots) {
        if (cases.size() != manifest.expectedCaseCount()) {
            throw new IllegalArgumentException(
                    "case count mismatch: expected=" + manifest.expectedCaseCount()
                            + ", actual=" + cases.size());
        }
        if (snapshots.size() != manifest.expectedCaseCount()) {
            throw new IllegalArgumentException(
                    "snapshot count mismatch: expected=" + manifest.expectedCaseCount()
                            + ", actual=" + snapshots.size());
        }

        Map<String, FrozenDecisionSnapshot> snapshotsByCaseId = new LinkedHashMap<>();
        Set<String> snapshotIds = new HashSet<>();
        for (FrozenDecisionSnapshot snapshot : snapshots) {
            validateSnapshot(snapshot, manifest);
            if (!snapshotIds.add(snapshot.snapshotId())) {
                throw new IllegalArgumentException(
                        "duplicate snapshotId=" + snapshot.snapshotId());
            }
            if (snapshotsByCaseId.putIfAbsent(snapshot.caseId(), snapshot) != null) {
                throw new IllegalArgumentException("duplicate snapshot caseId=" + snapshot.caseId());
            }
        }

        Set<String> caseIds = new HashSet<>();
        EnumMap<DecisionOutcome, Integer> classCounts = new EnumMap<>(DecisionOutcome.class);
        EnumSet<DecisionScenario> scenarios = EnumSet.noneOf(DecisionScenario.class);
        int nonEmptyRefuseCases = 0;
        for (AnswerabilityDecisionCase item : cases) {
            validateCase(item);
            if (!caseIds.add(item.caseId())) {
                throw new IllegalArgumentException("duplicate decision caseId=" + item.caseId());
            }
            FrozenDecisionSnapshot snapshot = snapshotsByCaseId.get(item.caseId());
            if (snapshot == null) {
                throw new IllegalArgumentException(
                        "decision case has no frozen snapshot: " + item.caseId());
            }
            validateCaseAgainstSnapshot(item, snapshot);
            classCounts.merge(item.expected().decision(), 1, Integer::sum);
            scenarios.add(item.scenario());
            if (item.expected().decision() == DecisionOutcome.REFUSE
                    && !snapshot.candidates().isEmpty()) {
                nonEmptyRefuseCases++;
            }
        }
        Set<String> extraSnapshots = new HashSet<>(snapshotsByCaseId.keySet());
        extraSnapshots.removeAll(caseIds);
        if (!extraSnapshots.isEmpty()) {
            throw new IllegalArgumentException(
                    "frozen snapshots contain unknown case IDs: " + extraSnapshots);
        }
        for (DecisionOutcome outcome : DecisionOutcome.values()) {
            int required = manifest.minimumClassCounts().getOrDefault(outcome, 0);
            int actual = classCounts.getOrDefault(outcome, 0);
            if (actual < required) {
                throw new IllegalArgumentException(
                        outcome + " case count is below manifest minimum: required="
                                + required + ", actual=" + actual);
            }
        }
        if (!scenarios.containsAll(manifest.requiredScenarios())) {
            EnumSet<DecisionScenario> missing = EnumSet.copyOf(manifest.requiredScenarios());
            missing.removeAll(scenarios);
            throw new IllegalArgumentException("required decision scenarios are missing: " + missing);
        }
        if (nonEmptyRefuseCases < manifest.minimumNonEmptyRefuseCases()) {
            throw new IllegalArgumentException(
                    "non-empty REFUSE cases are below manifest minimum: required="
                            + manifest.minimumNonEmptyRefuseCases()
                            + ", actual=" + nonEmptyRefuseCases);
        }
        return new FormalDecisionDataset(manifest, cases, snapshotsByCaseId);
    }

    private void validateManifest(DecisionDatasetManifest manifest) {
        requireText(manifest.schemaVersion(), "manifest.schemaVersion");
        if (!MANIFEST_SCHEMA.equals(manifest.schemaVersion())) {
            throw new IllegalArgumentException(
                    "manifest.schemaVersion must be " + MANIFEST_SCHEMA);
        }
        requireText(manifest.datasetId(), "manifest.datasetId");
        if (!FORMAL_DATASET_KIND.equals(manifest.datasetKind())) {
            throw new IllegalArgumentException(
                    "formal decision evaluation requires datasetKind=" + FORMAL_DATASET_KIND);
        }
        if (!PRIVATE_LOCAL.equals(manifest.dataClassification())) {
            throw new IllegalArgumentException(
                    "formal decision evaluation requires dataClassification="
                            + PRIVATE_LOCAL);
        }
        if (manifest.split() != DatasetSplit.DEVELOPMENT) {
            throw new IllegalArgumentException(
                    "Step 3.2 threshold sweep only accepts DEVELOPMENT data");
        }
        requireText(manifest.caseFile(), "manifest.caseFile");
        requireSha256(manifest.caseSha256(), "manifest.caseSha256");
        requireText(manifest.snapshotFile(), "manifest.snapshotFile");
        requireSha256(manifest.snapshotSha256(), "manifest.snapshotSha256");
        requireSha256(manifest.corpusSha256(), "manifest.corpusSha256");
        requireSha256(
                manifest.configurationSha256(),
                "manifest.configurationSha256");
        if (manifest.expectedCaseCount() <= 0) {
            throw new IllegalArgumentException("manifest.expectedCaseCount must be positive");
        }
        for (DecisionOutcome outcome : DecisionOutcome.values()) {
            Integer minimum = manifest.minimumClassCounts().get(outcome);
            if (minimum == null || minimum <= 0) {
                throw new IllegalArgumentException(
                        "manifest.minimumClassCounts needs a positive value for " + outcome);
            }
        }
        EnumSet<DecisionScenario> allScenarios = EnumSet.allOf(DecisionScenario.class);
        if (!manifest.requiredScenarios().containsAll(allScenarios)) {
            throw new IllegalArgumentException(
                    "manifest.requiredScenarios must contain all seven Step 3.2 scenarios");
        }
        if (manifest.minimumNonEmptyRefuseCases() <= 0) {
            throw new IllegalArgumentException(
                    "manifest.minimumNonEmptyRefuseCases must be positive");
        }
        requireText(manifest.pipelineFingerprint(), "manifest.pipelineFingerprint");
        requireText(manifest.scoreKind(), "manifest.scoreKind");
        EvidenceScoreKind scoreKind;
        try {
            scoreKind = EvidenceScoreKind.valueOf(manifest.scoreKind());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "manifest.scoreKind must be a production EvidenceScoreKind",
                    error);
        }
        if (scoreKind == EvidenceScoreKind.NONE) {
            throw new IllegalArgumentException(
                    "manifest.scoreKind cannot be NONE for a threshold sweep");
        }
        if (!HIGHER_IS_BETTER.equals(manifest.scoreDirection())) {
            throw new IllegalArgumentException(
                    "manifest.scoreDirection must be " + HIGHER_IS_BETTER);
        }
        requireText(manifest.evaluatorVersion(), "manifest.evaluatorVersion");
        requireText(manifest.reviewProtocol(), "manifest.reviewProtocol");
        if (!QUALITY_GATES_APPROVED.equals(manifest.qualityGateApproval())) {
            throw new IllegalArgumentException(
                    "manifest quality gates must be explicitly precommitted and approved");
        }
        validateQualityGates(manifest.qualityGates());
    }

    private void validateQualityGates(DecisionQualityGateConfig gates) {
        if (gates == null) {
            throw new IllegalArgumentException("manifest.qualityGates is required");
        }
        if (gates.criticalUnsafeAnswerCountMax() < 0) {
            throw new IllegalArgumentException(
                    "criticalUnsafeAnswerCountMax must be non-negative");
        }
        requireUnitInterval(gates.unsafeAnswerRateMax(), "unsafeAnswerRateMax");
        requireUnitInterval(gates.falseRefusalRateMax(), "falseRefusalRateMax");
        requireUnitInterval(
                gates.unnecessaryClarifyRateMax(), "unnecessaryClarifyRateMax");
        requireUnitInterval(gates.answerRecallMin(), "answerRecallMin");
        requireUnitInterval(gates.clarifyRecallMin(), "clarifyRecallMin");
        requireUnitInterval(gates.refuseRecallMin(), "refuseRecallMin");
    }

    private void validateCase(AnswerabilityDecisionCase item) {
        String path = "case[" + item.caseId() + "]";
        if (!CASE_SCHEMA.equals(item.schemaVersion())) {
            throw new IllegalArgumentException(path + ".schemaVersion must be " + CASE_SCHEMA);
        }
        requireText(item.caseId(), path + ".caseId");
        if (item.split() != DatasetSplit.DEVELOPMENT) {
            throw new IllegalArgumentException(path + ".split must be DEVELOPMENT");
        }
        if (item.severity() == null || item.scenario() == null) {
            throw new IllegalArgumentException(path + " needs severity and scenario");
        }
        if (item.input() == null || item.input().constraints() == null) {
            throw new IllegalArgumentException(path + ".input and constraints are required");
        }
        requireText(item.input().question(), path + ".input.question");
        for (AnswerabilityDecisionCase.HistoryMessage message : item.input().history()) {
            if (message == null
                    || (!"user".equals(message.role()) && !"assistant".equals(message.role()))) {
                throw new IllegalArgumentException(
                        path + ".input.history role must be user or assistant");
            }
            requireText(message.content(), path + ".input.history.content");
        }
        if (item.expected() == null || item.expected().decision() == null) {
            throw new IllegalArgumentException(path + ".expected.decision is required");
        }
        requireText(item.expected().reasonCode(), path + ".expected.reasonCode");
        switch (item.expected().decision()) {
            case ANSWER -> {
                if (item.expected().requiredEvidenceIds().isEmpty()) {
                    throw new IllegalArgumentException(
                            path + " ANSWER needs requiredEvidenceIds");
                }
                requireBlank(item.expected().clarificationTarget(),
                        path + " ANSWER cannot have clarificationTarget");
            }
            case CLARIFY -> {
                if (!item.expected().requiredEvidenceIds().isEmpty()) {
                    throw new IllegalArgumentException(
                            path + " CLARIFY cannot require positive evidence");
                }
                requireText(item.expected().clarificationTarget(),
                        path + " CLARIFY needs clarificationTarget");
            }
            case REFUSE -> {
                if (!item.expected().requiredEvidenceIds().isEmpty()) {
                    throw new IllegalArgumentException(
                            path + " REFUSE cannot require positive evidence");
                }
                requireBlank(item.expected().clarificationTarget(),
                        path + " REFUSE cannot have clarificationTarget");
            }
        }
        if (item.review() == null
                || !HUMAN_CONFIRMED.equals(item.review().status())
                || !HUMAN_REVIEWER.equals(item.review().reviewerType())) {
            throw new IllegalArgumentException(
                    path + " must have an explicit HUMAN_CONFIRMED human review");
        }
        requireText(item.review().reviewerId(), path + ".review.reviewerId");
        requireText(item.review().notes(), path + ".review.notes");
        try {
            OffsetDateTime.parse(item.review().reviewedAt());
        } catch (DateTimeParseException | NullPointerException error) {
            throw new IllegalArgumentException(
                    path + ".review.reviewedAt must be ISO-8601 with an offset", error);
        }
        if (item.tags().isEmpty()) {
            throw new IllegalArgumentException(path + " needs at least one tag");
        }
    }

    private void validateSnapshot(
            FrozenDecisionSnapshot snapshot,
            DecisionDatasetManifest manifest) {
        String path = "snapshot[" + snapshot.caseId() + "]";
        if (!SNAPSHOT_SCHEMA.equals(snapshot.schemaVersion())) {
            throw new IllegalArgumentException(
                    path + ".schemaVersion must be " + SNAPSHOT_SCHEMA);
        }
        requireText(snapshot.caseId(), path + ".caseId");
        requireText(snapshot.snapshotId(), path + ".snapshotId");
        if (!manifest.pipelineFingerprint().equals(snapshot.pipelineFingerprint())) {
            throw new IllegalArgumentException(path + ".pipelineFingerprint mismatch");
        }
        if (!manifest.scoreKind().equals(snapshot.scoreKind())) {
            throw new IllegalArgumentException(path + ".scoreKind mismatch");
        }
        if (!manifest.scoreDirection().equals(snapshot.scoreDirection())) {
            throw new IllegalArgumentException(path + ".scoreDirection mismatch");
        }
        requireText(snapshot.actualReranker(), path + ".actualReranker");
        RerankExecutionResult.Mode actualReranker;
        try {
            actualReranker = RerankExecutionResult.Mode.valueOf(
                    snapshot.actualReranker());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    path + ".actualReranker must be a production reranker mode",
                    error);
        }
        if (manifest.scoreKind().equals(EvidenceScoreKind.REMOTE_RERANK.name())
                && actualReranker != RerankExecutionResult.Mode.REMOTE) {
            throw new IllegalArgumentException(
                    path + " REMOTE_RERANK requires actualReranker=REMOTE");
        }
        if (manifest.scoreKind().equals(EvidenceScoreKind.LOCAL_RERANK.name())
                && actualReranker != RerankExecutionResult.Mode.LOCAL) {
            throw new IllegalArgumentException(
                    path + " LOCAL_RERANK requires actualReranker=LOCAL");
        }
        if (snapshot.candidateK() <= 0
                || snapshot.finalK() <= 0
                || snapshot.finalK() > snapshot.candidateK()) {
            throw new IllegalArgumentException(path + " needs candidateK >= finalK > 0");
        }
        if (snapshot.candidates().size() > snapshot.finalK()) {
            throw new IllegalArgumentException(path + " has more rows than finalK");
        }
        Set<String> evidenceIds = new HashSet<>();
        Set<Long> chunkIds = new HashSet<>();
        int expectedRank = 1;
        for (FrozenDecisionSnapshot.SnapshotCandidate candidate : snapshot.candidates()) {
            if (candidate == null) {
                throw new IllegalArgumentException(path + " contains a null candidate");
            }
            requireText(candidate.evidenceId(), path + ".candidate.evidenceId");
            if (!evidenceIds.add(candidate.evidenceId())) {
                throw new IllegalArgumentException(
                        path + " contains duplicate evidenceId="
                                + candidate.evidenceId());
            }
            if (candidate.chunkId() == null || candidate.chunkId() <= 0L) {
                throw new IllegalArgumentException(
                        path + ".candidate.chunkId must be positive");
            }
            if (!chunkIds.add(candidate.chunkId())) {
                throw new IllegalArgumentException(
                        path + " contains duplicate chunkId=" + candidate.chunkId());
            }
            if (candidate.documentId() != null && candidate.documentId() <= 0L) {
                throw new IllegalArgumentException(
                        path + ".candidate.documentId must be positive or null");
            }
            requireText(candidate.content(), path + ".candidate.content");
            if (candidate.sourcePage() != null && candidate.sourcePage() <= 0) {
                throw new IllegalArgumentException(
                        path + ".candidate.sourcePage must be positive or null");
            }
            if (candidate.rank() != expectedRank++) {
                throw new IllegalArgumentException(
                        path + " candidate ranks must be contiguous and ordered from 1");
            }
            if (!Double.isFinite(candidate.decisionScore())) {
                throw new IllegalArgumentException(path + " contains a non-finite decisionScore");
            }
        }
    }

    private void validateCaseAgainstSnapshot(
            AnswerabilityDecisionCase item,
            FrozenDecisionSnapshot snapshot) {
        Set<String> candidateIds = new HashSet<>();
        snapshot.candidates().forEach(
                candidate -> candidateIds.add(candidate.evidenceId()));
        if (!candidateIds.containsAll(item.expected().requiredEvidenceIds())) {
            throw new IllegalArgumentException(
                    item.caseId() + " ANSWER truth references evidence outside its frozen snapshot");
        }
        if (!candidateIds.containsAll(item.expected().confusingEvidenceIds())) {
            throw new IllegalArgumentException(
                    item.caseId() + " confusing evidence is outside its frozen snapshot");
        }
        if (item.scenario() == DecisionScenario.NO_RETRIEVAL
                && !snapshot.candidates().isEmpty()) {
            throw new IllegalArgumentException(
                    item.caseId() + " NO_RETRIEVAL must have an empty frozen snapshot");
        }
        if (item.scenario() == DecisionScenario.LOW_RELEVANCE
                && snapshot.candidates().isEmpty()) {
            throw new IllegalArgumentException(
                    item.caseId() + " LOW_RELEVANCE must contain retrieved distractors");
        }
    }

    private <T> List<T> readJsonLines(Path path, Class<T> type) throws IOException {
        List<T> values = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            try {
                values.add(mapper.readValue(line, type));
            } catch (IOException error) {
                throw new IllegalArgumentException(
                        path.getFileName() + " line " + lineNumber + " is invalid", error);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(path.getFileName() + " must not be empty");
        }
        return List.copyOf(values);
    }

    private Path resolveDatasetFile(Path root, String relativeName, String field) {
        Path relative = Path.of(relativeName);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("manifest." + field + " must be relative");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(
                    "manifest." + field + " escapes the dataset or is missing");
        }
        return resolved;
    }

    private void requireHash(Path path, String expected, String field) throws IOException {
        String actual = sha256(Files.readAllBytes(path));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    field + " mismatch for " + path.getFileName());
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK must provide SHA-256", impossible);
        }
    }

    private void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private void requireBlank(String value, String message) {
        if (value != null && !value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite and in [0, 1]");
        }
    }
}
