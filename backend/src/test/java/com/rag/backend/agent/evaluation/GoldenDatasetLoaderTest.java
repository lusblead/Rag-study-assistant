package com.rag.backend.agent.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 从 JSONL 加载并严格校验黄金评测集，发现坏数据时立即失败。
class GoldenDatasetLoaderTest {
    private final GoldenDatasetLoader loader = new GoldenDatasetLoader(
            new ObjectMapper().configure(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    true
            )
    );

    @Test
    // 验证版本化 JSONL 能完整加载，并保留 caseId、可回答性和证据真值。
    void loadsAndValidatesVersionedDataset() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/rag-eval/golden-v1.jsonl")) {
            List<GoldenRagCase> cases = loader.load(input);
            assertEquals(6, cases.size());
            assertTrue(cases.stream().anyMatch(c -> c.answerability() == Answerability.UNANSWERABLE));
            assertTrue(cases.stream().anyMatch(c -> c.tags().contains("multi-hop")));
        }
    }

    @Test
    // 构造重复 caseId，确认 Loader 在 Runner 启动前就拒绝不唯一数据。
    void rejectsDuplicateCaseIdBeforeEvaluationStarts() {
        String line = """
                {"schemaVersion":"rag-golden-v2","caseId":"DUP","split":"DEVELOPMENT","severity":"MEDIUM","courseId":1,"question":"q","relevantChunkIds":[1],"relevantEvidenceSpans":[{"documentKey":"doc-1","documentHash":"sha256:S","parseArtifactDigest":"sha256:P","pageNo":1,"sectionPath":"","startOffset":0,"endOffset":10,"quoteHash":"sha256:Q1"}],"referenceClaims":["c"],"answerability":"ANSWERABLE","mustCite":true,"tags":["x"]}
                {"schemaVersion":"rag-golden-v2","caseId":"DUP","split":"DEVELOPMENT","severity":"MEDIUM","courseId":1,"question":"q2","relevantChunkIds":[2],"relevantEvidenceSpans":[{"documentKey":"doc-1","documentHash":"sha256:S","parseArtifactDigest":"sha256:P","pageNo":1,"sectionPath":"","startOffset":10,"endOffset":20,"quoteHash":"sha256:Q2"}],"referenceClaims":["c2"],"answerability":"ANSWERABLE","mustCite":true,"tags":["x"]}
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> loader.load(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)))
        );
        assertTrue(error.getMessage().contains("duplicate caseId=DUP"));
    }
}
