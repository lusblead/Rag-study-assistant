package com.rag.backend.agent.evaluation.compile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.evaluation.Answerability;
import com.rag.backend.agent.evaluation.DatasetSplit;
import com.rag.backend.agent.evaluation.GoldenHistoryMessage;
import com.rag.backend.agent.evaluation.GoldenRagCase;
import com.rag.backend.agent.evaluation.Severity;
import com.rag.backend.agent.evaluation.model.EvidenceSpan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// 把作者 case 与冻结索引映射编译成 Runner 唯一读取的 GoldenRagCase v2 与 rubric。
public final class EvalCaseCompiler {
    private static final String COMPILER_VERSION = "eval-case-compiler-v1";

    private static final Set<String> TOP_FIELDS = Set.of(
            "id", "template_status", "domain", "category", "split", "severity",
            "enabled", "description", "input", "fixtures", "expected",
            "forbidden", "graders", "tags", "provenance"
    );
    private static final Set<String> INPUT_FIELDS =
            Set.of("course_key", "query", "history", "top_k");
    private static final Set<String> FIXTURE_FIELDS =
            Set.of("corpus_revision", "required_evidence_ids", "acceptable_evidence_sets");
    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "answerability", "required_evidence_ids", "reference_claims", "must_cite",
            "forbidden_evidence_ids", "allowed_but_untrusted_evidence_ids",
            "minimum_relevance_grade", "maximum_supported_evidence_count"
    );

    private final ObjectMapper mapper;

    public EvalCaseCompiler(ObjectMapper mapper) {
        this.mapper = mapper.copy().configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                true
        );
    }

    public CompileResult compile(CompileRequest request) throws IOException {
        requireFreshOutput(request.goldenOutput());
        requireFreshOutput(request.rubricOutput());
        requireFreshOutput(request.manifestOutput());

        Map<String, Long> courseIds = mapper.readValue(
                request.courseMap().toFile(),
                new TypeReference<Map<String, Long>>() { }
        );
        Map<String, EvidenceIndexMapping> evidenceById =
                readEvidenceMappings(request.evidenceMap());
        List<JsonNode> sources = readJsonLines(request.authorCases());
        sources.sort(Comparator.comparing(node -> requiredText(node, "id", "case")));

        Set<String> seenCaseIds = new HashSet<>();
        List<GoldenRagCase> goldenCases = new ArrayList<>();
        List<CompiledRubric> rubrics = new ArrayList<>();

        for (JsonNode source : sources) {
            assertAllowedFields(source, TOP_FIELDS, "case");
            assertAllowedFields(requiredObject(source, "input", "case"), INPUT_FIELDS, "input");
            assertAllowedFields(requiredObject(source, "fixtures", "case"), FIXTURE_FIELDS, "fixtures");
            assertAllowedFields(requiredObject(source, "expected", "case"), EXPECTED_FIELDS, "expected");

            String caseId = requiredText(source, "id", "case");
            if (!seenCaseIds.add(caseId)) {
                throw new IllegalArgumentException("duplicate case id: " + caseId);
            }
            if (!source.path("enabled").asBoolean(true)) {
                continue;
            }
            if ("EXAMPLE_NOT_MEASURED".equals(source.path("template_status").asText())) {
                throw new IllegalArgumentException(
                        caseId + " is still a template; freeze and review it before compiling"
                );
            }

            JsonNode input = source.get("input");
            JsonNode fixtures = source.get("fixtures");
            JsonNode expected = source.get("expected");
            String courseKey = requiredText(input, "course_key", caseId + ".input");
            Long courseId = courseIds.get(courseKey);
            if (courseId == null || courseId <= 0) {
                throw new IllegalArgumentException(
                        caseId + " has no positive courseId mapping for " + courseKey
                );
            }

            String outcome = requiredText(expected, "answerability", caseId + ".expected");
            if (!Set.of("ANSWER", "CLARIFY", "REFUSE").contains(outcome)) {
                throw new IllegalArgumentException(caseId + " has invalid answerability=" + outcome);
            }
            Set<String> requiredEvidenceIds = readTextSet(
                    expected.has("required_evidence_ids")
                            ? expected.get("required_evidence_ids")
                            : fixtures.path("required_evidence_ids"),
                    caseId + ".required_evidence_ids"
            );
            if ("ANSWER".equals(outcome) && requiredEvidenceIds.isEmpty()) {
                throw new IllegalArgumentException(caseId + " ANSWER needs required evidence");
            }
            if (!"ANSWER".equals(outcome) && !requiredEvidenceIds.isEmpty()) {
                throw new IllegalArgumentException(caseId + " non-answer case cannot require evidence");
            }

            Set<Long> chunkIds = new TreeSet<>();
            List<EvidenceSpan> spans = new ArrayList<>();
            for (String evidenceId : requiredEvidenceIds) {
                EvidenceSpan span = EvidenceSpanResolver.resolveUnique(
                        evidenceById, evidenceId, courseKey);
                chunkIds.addAll(evidenceById.get(evidenceId).chunkIds());
                spans.add(span);
            }

            GoldenRagCase golden = new GoldenRagCase(
                    "rag-golden-v2",
                    caseId,
                    DatasetSplit.valueOf(requiredText(source, "split", caseId).toUpperCase()),
                    Severity.valueOf(requiredText(source, "severity", caseId).toUpperCase()),
                    courseId,
                    requiredText(input, "query", caseId + ".input"),
                    readHistory(input.path("history"), caseId),
                    chunkIds,
                    spans,
                    readTextList(expected.path("reference_claims"), caseId + ".reference_claims"),
                    "ANSWER".equals(outcome)
                            ? Answerability.ANSWERABLE
                            : Answerability.UNANSWERABLE,
                    expected.path("must_cite").asBoolean("ANSWER".equals(outcome)),
                    readTextSet(source.path("tags"), caseId + ".tags")
            );
            goldenCases.add(golden);

            rubrics.add(new CompiledRubric(
                    caseId,
                    outcome,
                    requiredEvidenceIds,
                    readEvidenceSets(fixtures.path("acceptable_evidence_sets"), caseId),
                    readTextSet(expected.path("forbidden_evidence_ids"),
                            caseId + ".forbidden_evidence_ids"),
                    readTextList(source.path("forbidden"), caseId + ".forbidden"),
                    source.path("graders").deepCopy()
            ));
        }
        if (goldenCases.isEmpty()) {
            throw new IllegalArgumentException("no enabled cases were compiled");
        }

        byte[] goldenBytes = toJsonLines(goldenCases);
        byte[] rubricBytes = toJsonLines(rubrics);
        String goldenHash = sha256(goldenBytes);
        String rubricHash = sha256(rubricBytes);

        writeAtomically(request.goldenOutput(), goldenBytes);
        writeAtomically(request.rubricOutput(), rubricBytes);
        CompiledDatasetManifest manifest = new CompiledDatasetManifest(
                COMPILER_VERSION,
                goldenCases.size(),
                sha256(Files.readAllBytes(request.authorCases())),
                sha256(Files.readAllBytes(request.courseMap())),
                sha256(Files.readAllBytes(request.evidenceMap())),
                goldenHash,
                rubricHash
        );
        writeAtomically(
                request.manifestOutput(),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest)
        );
        return new CompileResult(goldenCases.size(), goldenHash, rubricHash);
    }

    private Map<String, EvidenceIndexMapping> readEvidenceMappings(Path path) throws IOException {
        Map<String, EvidenceIndexMapping> result = new HashMap<>();
        for (JsonNode node : readJsonLines(path)) {
            EvidenceIndexMapping mapping = mapper.treeToValue(node, EvidenceIndexMapping.class);
            if (result.putIfAbsent(mapping.evidenceId(), mapping) != null) {
                throw new IllegalArgumentException(
                        "duplicate evidenceId=" + mapping.evidenceId()
                );
            }
        }
        return result;
    }

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            try {
                result.add(mapper.readTree(line));
            } catch (IOException error) {
                throw new IllegalArgumentException(
                        path + " line " + lineNumber + " is invalid JSON", error
                );
            }
        }
        return result;
    }

    private byte[] toJsonLines(List<?> values) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            result.append(mapper.writeValueAsString(value)).append('\n');
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<GoldenHistoryMessage> readHistory(JsonNode node, String caseId) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(caseId + ".history must be an array");
        }
        List<GoldenHistoryMessage> result = new ArrayList<>();
        for (JsonNode item : node) {
            assertAllowedFields(item, Set.of("role", "content"), caseId + ".history");
            result.add(new GoldenHistoryMessage(
                    requiredText(item, "role", caseId + ".history"),
                    requiredText(item, "content", caseId + ".history")
            ));
        }
        return List.copyOf(result);
    }

    private static List<Set<String>> readEvidenceSets(JsonNode node, String caseId) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(caseId + ".acceptable_evidence_sets must be an array");
        }
        List<Set<String>> result = new ArrayList<>();
        for (JsonNode set : node) {
            result.add(readTextSet(set, caseId + ".acceptable_evidence_sets"));
        }
        return List.copyOf(result);
    }

    private static Set<String> readTextSet(JsonNode node, String field) {
        return new TreeSet<>(readTextList(node, field));
    }

    private static List<String> readTextList(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(field + " contains a blank/non-text value");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static JsonNode requiredObject(JsonNode owner, String field, String path) {
        JsonNode value = owner.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(path + "." + field + " must be an object");
        }
        return value;
    }

    private static String requiredText(JsonNode owner, String field, String path) {
        JsonNode value = owner.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(path + "." + field + " is required");
        }
        return value.asText();
    }

    private static void assertAllowedFields(JsonNode object, Set<String> allowed, String path) {
        if (!object.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(path + " has unknown field=" + field);
            }
        }
    }

    private static void requireFreshOutput(Path output) {
        if (Files.exists(output)) {
            throw new IllegalArgumentException(
                    "compile into a fresh run directory; output already exists: " + output
            );
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK must provide SHA-256", impossible);
        }
    }
}
