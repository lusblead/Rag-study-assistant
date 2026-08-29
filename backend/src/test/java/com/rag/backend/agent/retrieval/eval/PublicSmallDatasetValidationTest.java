package com.rag.backend.agent.retrieval.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicSmallDatasetValidationTest {

    @Test
    void validatesVersionedDatasetChecksumsOrderingAndCoverage() {
        PublicSmallDatasetSupport.Dataset dataset =
                PublicSmallDatasetSupport.loadAndValidate(datasetRoot());

        assertEquals("public-small-v1",
                dataset.manifest().path("datasetId").asText());
        assertEquals(dataset.corpus().size(),
                dataset.manifest().path("expectedChunkCount").asInt());
        assertEquals(dataset.cases().size(),
                dataset.manifest().path("expectedCaseCount").asInt());
        assertTrue(dataset.cases().stream().anyMatch(evalCase ->
                !evalCase.answerable()));
        assertTrue(dataset.cases().stream().flatMap(evalCase ->
                        evalCase.requiredEvidenceGroups().stream())
                .anyMatch(group -> group.chunkIds().size() > 1));
    }

    @Test
    void rejectsPrivateAndCredentialLikeContent() {
        List<String> forbiddenExamples = List.of(
                "Z:\\Users\\synthetic-person\\notes.txt",
"user@example.com",
                "13800138000",
                "+1 415 555 0100",
                "access_token=syntheticvalue123",
                "Obsidian Vault");

        for (String example : forbiddenExamples) {
            assertThrows(IllegalStateException.class,
                    () -> PublicSmallDatasetSupport.scanPrivacy(
                            "synthetic-negative-control", example));
        }
    }

    @Test
    void checksumTamperIsRejected(@TempDir Path tempDir) throws IOException {
        Path source = datasetRoot();
        Path copy = tempDir.resolve("public-small-v1");
        Files.createDirectories(copy);
        for (String name : List.of(
                "corpus.jsonl",
                "cases.jsonl",
                "embeddings.jsonl",
                "manifest.json",
                "checksums.sha256",
                "README.md",
                "SOURCES_AND_LICENSE.md")) {
            Files.copy(source.resolve(name), copy.resolve(name),
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.writeString(copy.resolve("corpus.jsonl"),
                "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> PublicSmallDatasetSupport.loadAndValidate(copy));
        assertTrue(error.getMessage().contains("SHA-256 mismatch"));
    }

    private Path datasetRoot() {
        return Path.of(System.getProperty(
                "rag.public.small.datasetDir",
                "backend/evals/datasets/public-small-v1"));
    }
}
