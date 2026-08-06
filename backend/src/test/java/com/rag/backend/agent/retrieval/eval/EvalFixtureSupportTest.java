package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvalFixtureSupportTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fixtureManifestValidatesSuccessfully() {
        Path fixtureDir = Path.of(
                "backend/src/test/resources/eval-fixtures/retrieval-smoke");
        JsonNode manifest = EvalFixtureSupport.validateFixtureManifest(fixtureDir);
        assertEquals(8, manifest.path("expectedCaseCount").asInt());
        assertEquals(12, manifest.path("expectedChunkCount").asInt());
        assertEquals(64, manifest.path("embeddingDimension").asInt());
    }

    @Test
    void fixtureChecksumTamperFails(@TempDir Path tempDir) throws IOException {
        Path fixtureDir = copyFixture(tempDir);
        Path corpus = fixtureDir.resolve("corpus.jsonl");
        String original = Files.readString(corpus, StandardCharsets.UTF_8);
        Files.writeString(corpus, original + "\n# tampered\n", StandardCharsets.UTF_8);
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> EvalFixtureSupport.validateFixtureManifest(fixtureDir));
        assertEquals(true, error.getMessage().contains("SHA-256 不匹配"));
    }

    @Test
    void missingFixtureCorpusFails(@TempDir Path tempDir) throws IOException {
        Path fixtureDir = copyFixture(tempDir);
        Files.delete(fixtureDir.resolve("corpus.jsonl"));
        assertThrows(IllegalStateException.class,
                () -> EvalFixtureSupport.validateFixtureManifest(fixtureDir));
    }

    @Test
    void missingFixtureCaseFileFails(@TempDir Path tempDir) throws IOException {
        Path fixtureDir = copyFixture(tempDir);
        Files.delete(fixtureDir.resolve("cases.jsonl"));
        assertThrows(IllegalStateException.class,
                () -> EvalFixtureSupport.validateFixtureManifest(fixtureDir));
    }

    @Test
    void serializedReportMustNotContainApiKey() {
        String report = "{\"ok\":true,\"apiKey\":\"sk-secret\",\"authorization\":\"Bearer abc\"}";
        assertThrows(IllegalStateException.class,
                () -> EvalFixtureSupport.assertReportContainsNoApiKey(report));
        EvalFixtureSupport.assertReportContainsNoApiKey("{\"ok\":true,\"apiKeyConfigured\":true}");
    }

    @Test
    void localDatasetManifestMismatchRejectsRun(@TempDir Path tempDir) throws IOException {
        Path datasetDir = tempDir.resolve("dataset");
        Files.createDirectories(datasetDir);
        String corpusLine = "{\"chunkId\":1,\"content\":\"a\"}";
        String caseLine = "{\"caseId\":\"c1\",\"query\":\"q\",\"answerable\":true}";
        Files.writeString(datasetDir.resolve("corpus.jsonl"),
                corpusLine + "\n", StandardCharsets.UTF_8);
        Files.writeString(datasetDir.resolve("standard_reviewed_100_retrieval.jsonl"),
                caseLine + "\n", StandardCharsets.UTF_8);
        Map<String, Object> manifest = Map.ofEntries(
                Map.entry("datasetId", "test"),
                Map.entry("schemaVersion", 1),
                Map.entry("corpusSha256", "0".repeat(64)),
                Map.entry("retrievalCaseFile", "standard_reviewed_100_retrieval.jsonl"),
                Map.entry("retrievalCaseSha256", "0".repeat(64)),
                Map.entry("expectedChunkCount", 1),
                Map.entry("expectedCaseCount", 1));
        Path manifestPath = datasetDir.resolve("manifest.json");
        Files.writeString(manifestPath,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> EvalFixtureSupport.validateLocalDatasetManifest(
                        datasetDir, manifestPath));
    }

    @Test
    void missingRequiredEnvFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> EvalFixtureSupport.requireNonBlankEnv(
                        "RAG_EVAL_MISSING_TEST_KEY"));
    }

    private Path copyFixture(Path tempDir) throws IOException {
        Path source = Path.of(
                "backend/src/test/resources/eval-fixtures/retrieval-smoke");
        Path target = tempDir.resolve("fixture");
        Files.createDirectories(target);
        for (String name : new String[] {
                "corpus.jsonl", "cases.jsonl", "embeddings.jsonl", "manifest.json"}) {
            Files.copy(source.resolve(name), target.resolve(name));
        }
        return target;
    }
}
