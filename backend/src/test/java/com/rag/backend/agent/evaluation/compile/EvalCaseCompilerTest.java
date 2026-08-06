package com.rag.backend.agent.evaluation.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvalCaseCompilerTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compilesDeterministicallyAndWritesManifestLast() throws Exception {
        Path cases = write("cases.jsonl", """
                {"id":"case-1","template_status":"READY","split":"frozen","severity":"high","enabled":true,"input":{"course_key":"course-a","query":"RRF 是什么？","history":[]},"fixtures":{"required_evidence_ids":["ev-1"],"acceptable_evidence_sets":[["ev-1"]]},"expected":{"answerability":"ANSWER","reference_claims":["RRF 使用排名融合"],"must_cite":true,"forbidden_evidence_ids":[]},"forbidden":[],"graders":[],"tags":["rrf"]}
                """);
        Path courseMap = write("course-map.json", "{\"course-a\":9001}");
        Path evidenceMap = write("evidence-map.jsonl", """
                {"evidenceId":"ev-1","courseKey":"course-a","chunkIds":[101],"span":{"documentKey":"doc-a","documentHash":"sha256:SOURCE","parseArtifactDigest":"sha256:PARSE","pageNo":1,"sectionPath":"RRF","startOffset":10,"endOffset":30,"quoteHash":"sha256:QUOTE"}}
                """);

        EvalCaseCompiler compiler = new EvalCaseCompiler(mapper);
        CompileResult first = compiler.compile(request(cases, courseMap, evidenceMap, "run-1"));
        CompileResult second = compiler.compile(request(cases, courseMap, evidenceMap, "run-2"));

        assertEquals(1, first.caseCount());
        assertEquals(first.goldenSha256(), second.goldenSha256());
        assertEquals(first.rubricSha256(), second.rubricSha256());
        assertEquals(1, Files.readAllLines(tempDir.resolve("run-1/golden.jsonl")).size());
        assertEquals(1, Files.readAllLines(tempDir.resolve("run-1/rubric.jsonl")).size());
        assertEquals(true, Files.exists(tempDir.resolve("run-1/manifest.json")));
    }

    @Test
    void missingEvidenceFailsWithoutCompletionManifest() throws Exception {
        Path cases = write("bad-cases.jsonl", """
                {"id":"case-bad","template_status":"READY","split":"frozen","severity":"high","enabled":true,"input":{"course_key":"course-a","query":"q","history":[]},"fixtures":{"required_evidence_ids":["missing"]},"expected":{"answerability":"ANSWER","reference_claims":["c"],"must_cite":true},"forbidden":[],"graders":[],"tags":["x"]}
                """);
        Path courseMap = write("bad-course-map.json", "{\"course-a\":9001}");
        Path evidenceMap = write("empty-evidence-map.jsonl", "# no mapping\n");
        CompileRequest request = request(cases, courseMap, evidenceMap, "failed-run");

        assertThrows(
                IllegalArgumentException.class,
                () -> new EvalCaseCompiler(mapper).compile(request)
        );
        assertFalse(Files.exists(request.manifestOutput()));
    }

    private CompileRequest request(Path cases, Path courseMap, Path evidenceMap, String run) {
        Path directory = tempDir.resolve(run);
        return new CompileRequest(
                cases,
                courseMap,
                evidenceMap,
                directory.resolve("golden.jsonl"),
                directory.resolve("rubric.jsonl"),
                directory.resolve("manifest.json")
        );
    }

    private Path write(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}
