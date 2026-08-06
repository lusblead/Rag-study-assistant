package com.rag.backend.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 从 JSONL 加载并严格校验黄金评测集，发现坏数据时立即失败。
public final class GoldenDatasetLoader {
    private final ObjectMapper objectMapper;

    public GoldenDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<GoldenRagCase> load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input);
        }
    }

    public List<GoldenRagCase> load(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("dataset input must not be null");
        }
        List<GoldenRagCase> cases = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                GoldenRagCase item = objectMapper.readValue(line, GoldenRagCase.class);
                validate(item, lineNumber, seenIds);
                cases.add(item);
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("golden dataset must contain at least one case");
        }
        return List.copyOf(cases);
    }

    private void validate(GoldenRagCase item, int lineNumber, Set<String> seenIds) {
        String prefix = "invalid golden case at line " + lineNumber + ": ";
        if (item.caseId() == null || item.caseId().isBlank()) {
            throw new IllegalArgumentException(prefix + "caseId is blank");
        }
        if (!"rag-golden-v2".equals(item.schemaVersion())) {
            throw new IllegalArgumentException(prefix + "schemaVersion must be rag-golden-v2");
        }
        if (!seenIds.add(item.caseId())) {
            throw new IllegalArgumentException(prefix + "duplicate caseId=" + item.caseId());
        }
        if (item.courseId() == null || item.courseId() <= 0) {
            throw new IllegalArgumentException(prefix + "courseId must be positive");
        }
        if (item.question() == null || item.question().isBlank()) {
            throw new IllegalArgumentException(prefix + "question is blank");
        }
        if (item.answerability() == null) {
            throw new IllegalArgumentException(prefix + "answerability is missing");
        }
        if (item.split() == null) {
            throw new IllegalArgumentException(prefix + "split is missing");
        }
        if (item.severity() == null) {
            throw new IllegalArgumentException(prefix + "severity is missing");
        }
        if (item.tags().isEmpty()) {
            throw new IllegalArgumentException(prefix + "at least one tag is required");
        }
        if (item.answerability() == Answerability.ANSWERABLE
                && item.relevantChunkIds().isEmpty()) {
            throw new IllegalArgumentException(prefix + "answerable case needs relevantChunkIds");
        }
        if (item.answerability() == Answerability.ANSWERABLE
                && item.relevantEvidenceSpans().isEmpty()) {
            throw new IllegalArgumentException(prefix + "answerable case needs relevantEvidenceSpans");
        }
        if (item.answerability() == Answerability.UNANSWERABLE
                && !item.relevantChunkIds().isEmpty()) {
            throw new IllegalArgumentException(prefix + "unanswerable case must not have relevantChunkIds");
        }
        if (item.answerability() == Answerability.UNANSWERABLE
                && !item.relevantEvidenceSpans().isEmpty()) {
            throw new IllegalArgumentException(prefix + "unanswerable case must not have relevantEvidenceSpans");
        }
        if (item.mustCite() && item.answerability() == Answerability.ANSWERABLE
                && item.referenceClaims().isEmpty()) {
            throw new IllegalArgumentException(prefix + "mustCite case needs referenceClaims");
        }
    }
}
