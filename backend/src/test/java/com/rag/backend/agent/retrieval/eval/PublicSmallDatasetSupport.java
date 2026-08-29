package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Loads and validates the repository-safe public-small-v1 retrieval dataset.
 * Validation is deliberately deterministic and runs before any ranking or report output.
 */
final class PublicSmallDatasetSupport {
    static final String DATASET_ID = "public-small-v1";
    static final int SCHEMA_VERSION = 1;
    static final long SEED = 20_260_809L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<PrivacyRule> PRIVACY_RULES = List.of(
            new PrivacyRule("absolute user path", Pattern.compile(
                    "(?i)(?:[a-z]:[\\\\/](?:users|documents and settings)[\\\\/]"
                            + "|/(?:users|home)/)[^\\s\\\"']+")),
            new PrivacyRule("email address", Pattern.compile(
                    "(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}"
                            + "(?![a-z0-9.-])")),
            new PrivacyRule("mobile number", Pattern.compile(
                    "(?<![a-z0-9])1[3-9]\\d{9}(?![a-z0-9])")),
            new PrivacyRule("international phone number", Pattern.compile(
                    "(?<![a-z0-9])\\+\\d{1,3}[ .-]?(?:\\d[ .-]?){7,12}\\d"
                            + "(?![a-z0-9])")),
            new PrivacyRule("credential-like value", Pattern.compile(
                    "(?i)(?:api[ _-]?key|access[ _-]?token|secret|password|passwd|bearer)"
                            + "\\s*[:=]\\s*[\\\"']?[a-z0-9_./+=-]{8,}")),
            new PrivacyRule("credential prefix", Pattern.compile(
                    "(?i)(?<![a-z0-9])(?:sk-[a-z0-9]{8,}|akia[0-9a-z]{16})"
                            + "(?![a-z0-9])")),
            new PrivacyRule("private knowledge-base marker", Pattern.compile(
                    "(?i)(?:obsidian[ _-]?vault|private[ _-]?vault|vault[\\\\/]"
                            + "|私有(?:知识库|笔记库))")));

    private PublicSmallDatasetSupport() {
    }

    static Dataset loadAndValidate(Path datasetDir) {
        Path root = datasetDir.toAbsolutePath().normalize();
        Path manifestPath = requireFile(root.resolve("manifest.json"), "manifest");
        Path corpusPath = requireFile(root.resolve("corpus.jsonl"), "corpus");
        Path casesPath = requireFile(root.resolve("cases.jsonl"), "cases");
        Path embeddingsPath = requireFile(root.resolve("embeddings.jsonl"), "embeddings");
        requireFile(root.resolve("checksums.sha256"), "checksums");
        requireFile(root.resolve("README.md"), "dataset README");
        requireFile(root.resolve("SOURCES_AND_LICENSE.md"), "source/license statement");

        JsonNode manifest = readJson(manifestPath);
        validateManifestContract(manifest);
        verifyChecksums(root, manifest);
        scanDatasetText(root);

        List<CorpusRow> corpus = readCorpus(corpusPath);
        List<EvalCase> cases = readCases(casesPath);
        Map<String, List<Double>> embeddings = readEmbeddings(embeddingsPath);
        validateCounts(manifest, corpus, cases);
        validateCorpus(corpus);
        validateCases(corpus, cases);
        validateEmbeddings(manifest, corpus, cases, embeddings);

        return new Dataset(
                manifest,
                corpus,
                cases,
                embeddings,
                sha256(corpusPath),
                sha256(casesPath),
                sha256(embeddingsPath));
    }

    static void verifyChecksums(Path root, JsonNode manifest) {
        Map<String, String> checksumFile = readChecksumFile(
                root.resolve("checksums.sha256"));
        Map<String, String> requiredFiles = new LinkedHashMap<>();
        requiredFiles.put("corpus", "corpus.jsonl");
        requiredFiles.put("cases", "cases.jsonl");
        requiredFiles.put("embeddings", "embeddings.jsonl");

        for (Map.Entry<String, String> entry : requiredFiles.entrySet()) {
            JsonNode fileNode = manifest.path("files").path(entry.getKey());
            String declaredName = fileNode.path("file").asText();
            if (!entry.getValue().equals(declaredName)) {
                throw invalid("manifest file name mismatch for " + entry.getKey());
            }
            String actual = sha256(requireFile(root.resolve(declaredName), declaredName));
            String manifestHash = fileNode.path("sha256").asText();
            if (!actual.equals(manifestHash)) {
                throw invalid("manifest SHA-256 mismatch for " + declaredName);
            }
            if (!actual.equals(checksumFile.get(declaredName))) {
                throw invalid("checksums.sha256 mismatch for " + declaredName);
            }
        }

        String manifestHash = sha256(root.resolve("manifest.json"));
        if (!manifestHash.equals(checksumFile.get("manifest.json"))) {
            throw invalid("checksums.sha256 mismatch for manifest.json");
        }
        Set<String> expectedNames = Set.of(
                "corpus.jsonl", "cases.jsonl", "embeddings.jsonl", "manifest.json");
        if (!checksumFile.keySet().equals(expectedNames)) {
            throw invalid("checksums.sha256 must contain exactly the four core files");
        }
    }

    static void scanPrivacy(String label, String text) {
        for (PrivacyRule rule : PRIVACY_RULES) {
            if (rule.pattern().matcher(text).find()) {
                throw invalid("privacy gate rejected " + label + ": " + rule.label());
            }
        }
    }

    private static void validateManifestContract(JsonNode manifest) {
        requireEquals(manifest, "datasetId", DATASET_ID);
        requireEquals(manifest, "schemaVersion", SCHEMA_VERSION);
        requireEquals(manifest, "seed", SEED);
        requireEquals(manifest, "candidateK", 8);
        requireEquals(manifest, "topK", 5);
        requireEquals(manifest, "indexType", "exact-cosine");
        requireEquals(manifest, "tieBreaker", "score-desc,chunkId-asc");
        requireEquals(manifest, "license", "CC0-1.0");
        requireEquals(manifest, "sourceType", "synthetic-original");
        if (manifest.path("datasetVersion").asText().isBlank()) {
            throw invalid("datasetVersion is blank");
        }
        if (manifest.path("embeddingDimension").asInt(-1) <= 0) {
            throw invalid("embeddingDimension must be positive");
        }
        if (!manifest.path("files").isObject()) {
            throw invalid("manifest files object is missing");
        }
    }

    private static void validateCounts(
            JsonNode manifest, List<CorpusRow> corpus, List<EvalCase> cases) {
        int expectedChunks = manifest.path("expectedChunkCount").asInt(-1);
        int expectedCases = manifest.path("expectedCaseCount").asInt(-1);
        if (expectedChunks != corpus.size()
                || manifest.path("files").path("corpus").path("records").asInt(-1)
                != corpus.size()) {
            throw invalid("corpus record count mismatch");
        }
        if (expectedCases != cases.size()
                || manifest.path("files").path("cases").path("records").asInt(-1)
                != cases.size()) {
            throw invalid("case record count mismatch");
        }
        int embeddingRecords = manifest.path("files").path("embeddings")
                .path("records").asInt(-1);
        if (embeddingRecords != corpus.size() + cases.size()) {
            throw invalid("embedding record count must equal corpus plus cases");
        }
    }

    private static void validateCorpus(List<CorpusRow> corpus) {
        if (corpus.isEmpty()) {
            throw invalid("corpus must not be empty");
        }
        long previousId = Long.MIN_VALUE;
        Set<String> content = new HashSet<>();
        for (CorpusRow row : corpus) {
            if (row.chunkId() <= previousId) {
                throw invalid("corpus rows must be strictly sorted by chunkId");
            }
            previousId = row.chunkId();
            if (row.chunkId() <= 0 || row.documentId() <= 0
                    || row.sourceId().isBlank() || row.title().isBlank()
                    || row.content().isBlank()) {
                throw invalid("corpus contains a blank or non-positive field");
            }
            String normalized = row.content().strip().replaceAll("\\s+", " ");
            if (!content.add(normalized)) {
                throw invalid("corpus contains duplicate content");
            }
        }
    }

    private static void validateCases(List<CorpusRow> corpus, List<EvalCase> cases) {
        if (cases.isEmpty()) {
            throw invalid("cases must not be empty");
        }
        Map<Long, CorpusRow> corpusById = new LinkedHashMap<>();
        corpus.forEach(row -> corpusById.put(row.chunkId(), row));
        Set<String> caseIds = new HashSet<>();
        Set<String> categories = new HashSet<>();
        String previousCaseId = "";
        int unanswerableCount = 0;
        boolean hasAlternativeGroup = false;
        boolean hasExpectedRerankImprovement = false;
        boolean hasExpectedRerankRegression = false;

        for (EvalCase evalCase : cases) {
            if (evalCase.caseId().compareTo(previousCaseId) <= 0) {
                throw invalid("case rows must be strictly sorted by caseId");
            }
            previousCaseId = evalCase.caseId();
            if (!caseIds.add(evalCase.caseId()) || evalCase.query().isBlank()
                    || evalCase.category().isBlank()) {
                throw invalid("case contains duplicate or blank identity fields");
            }
            categories.add(evalCase.category());
            requireSortedUnique(evalCase.relevantChunkIds(), "relevantChunkIds");
            requireSortedUnique(evalCase.acceptableChunkIds(), "acceptableChunkIds");
            requireSortedUnique(evalCase.confusingChunkIds(), "confusingChunkIds");
            requireSortedUniqueText(evalCase.requiredSourceIds(), "requiredSourceIds");
            requireSortedGroups(evalCase.requiredEvidenceGroups());
            validateRetrievalExpectations(evalCase, corpusById.keySet());
            hasExpectedRerankImprovement |= "improves".equals(
                    evalCase.retrievalExpectations().rerankEffect());
            hasExpectedRerankRegression |= "degrades".equals(
                    evalCase.retrievalExpectations().rerankEffect());

            Set<Long> referenced = new LinkedHashSet<>();
            referenced.addAll(evalCase.relevantChunkIds());
            referenced.addAll(evalCase.acceptableChunkIds());
            referenced.addAll(evalCase.confusingChunkIds());
            evalCase.requiredEvidenceGroups().forEach(group ->
                    referenced.addAll(group.chunkIds()));
            for (long chunkId : referenced) {
                if (!corpusById.containsKey(chunkId)) {
                    throw invalid(evalCase.caseId() + " references unknown chunk " + chunkId);
                }
            }

            if (!evalCase.answerable()) {
                unanswerableCount++;
                if (!evalCase.relevantChunkIds().isEmpty()
                        || !evalCase.acceptableChunkIds().isEmpty()
                        || !evalCase.requiredEvidenceGroups().isEmpty()
                        || !evalCase.requiredSourceIds().isEmpty()) {
                    throw invalid(evalCase.caseId()
                            + " unanswerable case has positive evidence");
                }
                continue;
            }

            if (evalCase.relevantChunkIds().isEmpty()
                    || evalCase.acceptableChunkIds().isEmpty()
                    || evalCase.requiredEvidenceGroups().isEmpty()
                    || evalCase.requiredSourceIds().isEmpty()) {
                throw invalid(evalCase.caseId() + " answerable case lacks evidence");
            }
            if (!evalCase.acceptableChunkIds().containsAll(evalCase.relevantChunkIds())) {
                throw invalid(evalCase.caseId()
                        + " acceptableChunkIds must include strict relevant chunks");
            }
            Set<Long> grouped = new LinkedHashSet<>();
            for (EvidenceGroup group : evalCase.requiredEvidenceGroups()) {
                if (group.chunkIds().isEmpty()) {
                    throw invalid(evalCase.caseId() + " contains an empty evidence group");
                }
                grouped.addAll(group.chunkIds());
                hasAlternativeGroup |= group.chunkIds().size() > 1;
            }
            if (!grouped.equals(new LinkedHashSet<>(evalCase.acceptableChunkIds()))) {
                throw invalid(evalCase.caseId()
                        + " evidence groups must cover exactly acceptableChunkIds");
            }
            Set<String> evidenceSources = new LinkedHashSet<>();
            grouped.forEach(id -> evidenceSources.add(corpusById.get(id).sourceId()));
            if (!evidenceSources.equals(new LinkedHashSet<>(evalCase.requiredSourceIds()))) {
                throw invalid(evalCase.caseId()
                        + " requiredSourceIds must exactly close over acceptable evidence");
            }
            if (!Collections.disjoint(
                    evalCase.acceptableChunkIds(), evalCase.confusingChunkIds())) {
                throw invalid(evalCase.caseId()
                        + " confusing chunks overlap acceptable evidence");
            }
        }

        if (categories.stream().noneMatch(value -> value.contains("single"))
                || categories.stream().noneMatch(value -> value.contains("multi"))
                || categories.stream().noneMatch(value -> value.contains("concept"))
                || categories.stream().noneMatch(value -> value.contains("unanswerable"))
                || categories.stream().noneMatch(value -> value.contains("alternative"))
                || categories.stream().noneMatch(value -> value.contains("rerank"))) {
            throw invalid("case categories do not cover the required families");
        }
        if (unanswerableCount < 2) {
            throw invalid("at least two unanswerable cases are required");
        }
        if (!hasAlternativeGroup) {
            throw invalid("at least one alternative evidence group is required");
        }
        if (!hasExpectedRerankImprovement || !hasExpectedRerankRegression) {
            throw invalid("frozen cases require measured rerank improvement and regression");
        }
    }

    private static void validateRetrievalExpectations(
            EvalCase evalCase, Set<Long> corpusChunkIds) {
        RetrievalExpectations expected = evalCase.retrievalExpectations();
        if (expected.exactCosineCandidateChunkIds().size() != 8
                || expected.rerankedTopChunkIds().size() != 5) {
            throw invalid(evalCase.caseId() + " retrieval expectation size mismatch");
        }
        if (new LinkedHashSet<>(expected.exactCosineCandidateChunkIds()).size() != 8
                || new LinkedHashSet<>(expected.rerankedTopChunkIds()).size() != 5) {
            throw invalid(evalCase.caseId() + " retrieval expectations contain duplicates");
        }
        if (!corpusChunkIds.containsAll(expected.exactCosineCandidateChunkIds())
                || !expected.exactCosineCandidateChunkIds()
                .containsAll(expected.rerankedTopChunkIds())) {
            throw invalid(evalCase.caseId() + " retrieval expectations are not corpus-closed");
        }
        if (!Set.of("improves", "degrades", "neutral", "not-applicable")
                .contains(expected.rerankEffect())) {
            throw invalid(evalCase.caseId() + " has invalid rerankEffect");
        }
        Integer before = firstRelevantRank(
                expected.exactCosineCandidateChunkIds(), evalCase.relevantChunkIds());
        Integer after = firstRelevantRank(
                expected.rerankedTopChunkIds(), evalCase.relevantChunkIds());
        if (!java.util.Objects.equals(before, expected.relevantBestRankBefore())
                || !java.util.Objects.equals(after, expected.relevantBestRankAfter())) {
            throw invalid(evalCase.caseId() + " relevant rank expectation mismatch");
        }
        if (!evalCase.answerable()
                && !"not-applicable".equals(expected.rerankEffect())) {
            throw invalid(evalCase.caseId() + " unanswerable rerank effect must be not-applicable");
        }
    }

    private static Integer firstRelevantRank(
            List<Long> ranked, List<Long> relevant) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) {
                return index + 1;
            }
        }
        return null;
    }

    private static void validateEmbeddings(
            JsonNode manifest,
            List<CorpusRow> corpus,
            List<EvalCase> cases,
            Map<String, List<Double>> embeddings) {
        int dimension = manifest.path("embeddingDimension").asInt();
        Set<String> expectedKeys = new LinkedHashSet<>();
        corpus.forEach(row -> expectedKeys.add("chunk:" + row.chunkId()));
        cases.forEach(evalCase -> expectedKeys.add("query:" + evalCase.caseId()));
        if (!embeddings.keySet().equals(expectedKeys)) {
            throw invalid("embedding keys do not exactly cover corpus and cases");
        }
        for (Map.Entry<String, List<Double>> entry : embeddings.entrySet()) {
            if (entry.getValue().size() != dimension) {
                throw invalid("embedding dimension mismatch for " + entry.getKey());
            }
            double normSquared = 0.0;
            for (double value : entry.getValue()) {
                if (!Double.isFinite(value)) {
                    throw invalid("non-finite embedding value for " + entry.getKey());
                }
                normSquared += value * value;
            }
            if (Math.abs(Math.sqrt(normSquared) - 1.0) > 0.000_1) {
                throw invalid("embedding must be a unit vector for " + entry.getKey());
            }
        }
    }

    private static List<CorpusRow> readCorpus(Path path) {
        List<CorpusRow> result = new ArrayList<>();
        for (JsonNode node : readJsonLines(path)) {
            result.add(new CorpusRow(
                    node.path("chunkId").asLong(),
                    node.path("documentId").asLong(),
                    node.path("sourceId").asText(),
                    node.path("title").asText(),
                    node.path("content").asText()));
        }
        return List.copyOf(result);
    }

    private static List<EvalCase> readCases(Path path) {
        List<EvalCase> result = new ArrayList<>();
        for (JsonNode node : readJsonLines(path)) {
            List<EvidenceGroup> groups = new ArrayList<>();
            node.path("requiredEvidenceGroups").forEach(group ->
                    groups.add(new EvidenceGroup(
                            group.path("groupId").asText(),
                            longList(group.path("chunkIds")))));
            result.add(new EvalCase(
                    node.path("caseId").asText(),
                    node.path("query").asText(),
                    node.path("category").asText(),
                    node.path("answerable").asBoolean(),
                    longList(node.path("relevantChunkIds")),
                    longList(node.path("acceptableChunkIds")),
                    List.copyOf(groups),
                    textList(node.path("requiredSourceIds")),
                    longList(node.path("confusingChunkIds")),
                    retrievalExpectations(node.path("retrievalExpectations"))));
        }
        return List.copyOf(result);
    }

    private static Map<String, List<Double>> readEmbeddings(Path path) {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        String previousKey = "";
        for (JsonNode node : readJsonLines(path)) {
            String key = node.path("embeddingId").asText();
            if (key.compareTo(previousKey) <= 0 || result.containsKey(key)) {
                throw invalid("embedding rows must be strictly sorted by key");
            }
            previousKey = key;
            List<Double> vector = new ArrayList<>();
            node.path("vector").forEach(value -> vector.add(value.asDouble()));
            result.put(key, List.copyOf(vector));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<JsonNode> readJsonLines(Path path) {
        try {
            List<JsonNode> result = new ArrayList<>();
            int lineNumber = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    result.add(JSON.readTree(line));
                } catch (IOException e) {
                    throw invalid(path.getFileName() + " invalid JSON at line " + lineNumber);
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + path.getFileName(), e);
        }
    }

    private static JsonNode readJson(Path path) {
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse " + path.getFileName(), e);
        }
    }

    private static Map<String, String> readChecksumFile(Path path) {
        try {
            Map<String, String> result = new TreeMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (!line.matches("[0-9a-f]{64}  [A-Za-z0-9._-]+")) {
                    throw invalid("invalid checksums.sha256 line");
                }
                String hash = line.substring(0, 64);
                String name = line.substring(66);
                if (result.put(name, hash) != null) {
                    throw invalid("duplicate checksums.sha256 entry for " + name);
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read checksums.sha256", e);
        }
    }

    private static void scanDatasetText(Path root) {
        for (String name : List.of(
                "corpus.jsonl",
                "cases.jsonl",
                "manifest.json",
                "README.md",
                "SOURCES_AND_LICENSE.md")) {
            try {
                scanPrivacy(name, Files.readString(root.resolve(name), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to privacy-scan " + name, e);
            }
        }
    }

    private static Path requireFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw invalid(label + " file is missing");
        }
        return path;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to calculate SHA-256", e);
        }
    }

    private static List<Long> longList(JsonNode values) {
        List<Long> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asLong()));
        return List.copyOf(result);
    }

    private static List<String> textList(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static RetrievalExpectations retrievalExpectations(JsonNode node) {
        return new RetrievalExpectations(
                longList(node.path("exactCosineCandidateChunkIds")),
                longList(node.path("rerankedTopChunkIds")),
                nullableInt(node.path("relevantBestRankBefore")),
                nullableInt(node.path("relevantBestRankAfter")),
                node.path("rerankEffect").asText());
    }

    private static Integer nullableInt(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static void requireSortedUnique(List<Long> values, String label) {
        long previous = Long.MIN_VALUE;
        for (long value : values) {
            if (value <= previous) {
                throw invalid(label + " must be strictly sorted and unique");
            }
            previous = value;
        }
    }

    private static void requireSortedUniqueText(List<String> values, String label) {
        String previous = "";
        for (String value : values) {
            if (value.isBlank() || value.compareTo(previous) <= 0) {
                throw invalid(label + " must be strictly sorted and unique");
            }
            previous = value;
        }
    }

    private static void requireSortedGroups(List<EvidenceGroup> groups) {
        String previous = "";
        for (EvidenceGroup group : groups) {
            if (group.groupId().isBlank() || group.groupId().compareTo(previous) <= 0) {
                throw invalid("evidence groups must be strictly sorted by groupId");
            }
            previous = group.groupId();
            requireSortedUnique(group.chunkIds(), "evidence group chunkIds");
        }
    }

    private static void requireEquals(JsonNode root, String field, String expected) {
        if (!expected.equals(root.path(field).asText())) {
            throw invalid(field + " must equal " + expected);
        }
    }

    private static void requireEquals(JsonNode root, String field, long expected) {
        if (root.path(field).asLong(Long.MIN_VALUE) != expected) {
            throw invalid(field + " must equal " + expected);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid public-small-v1 dataset: " + message);
    }

    record Dataset(
            JsonNode manifest,
            List<CorpusRow> corpus,
            List<EvalCase> cases,
            Map<String, List<Double>> embeddings,
            String corpusSha256,
            String casesSha256,
            String embeddingsSha256) {
        Dataset {
            corpus = List.copyOf(corpus);
            cases = List.copyOf(cases);
            embeddings = Collections.unmodifiableMap(new LinkedHashMap<>(embeddings));
        }
    }

    record CorpusRow(
            long chunkId,
            long documentId,
            String sourceId,
            String title,
            String content) {
    }

    record EvidenceGroup(String groupId, List<Long> chunkIds) {
        EvidenceGroup {
            chunkIds = List.copyOf(chunkIds);
        }
    }

    record EvalCase(
            String caseId,
            String query,
            String category,
            boolean answerable,
            List<Long> relevantChunkIds,
            List<Long> acceptableChunkIds,
            List<EvidenceGroup> requiredEvidenceGroups,
            List<String> requiredSourceIds,
            List<Long> confusingChunkIds,
            RetrievalExpectations retrievalExpectations) {
        EvalCase {
            relevantChunkIds = List.copyOf(relevantChunkIds);
            acceptableChunkIds = List.copyOf(acceptableChunkIds);
            requiredEvidenceGroups = List.copyOf(requiredEvidenceGroups);
            requiredSourceIds = List.copyOf(requiredSourceIds);
            confusingChunkIds = List.copyOf(confusingChunkIds);
        }
    }

    record RetrievalExpectations(
            List<Long> exactCosineCandidateChunkIds,
            List<Long> rerankedTopChunkIds,
            Integer relevantBestRankBefore,
            Integer relevantBestRankAfter,
            String rerankEffect) {
        RetrievalExpectations {
            exactCosineCandidateChunkIds = List.copyOf(exactCosineCandidateChunkIds);
            rerankedTopChunkIds = List.copyOf(rerankedTopChunkIds);
        }
    }

    private record PrivacyRule(String label, Pattern pattern) {
    }
}
