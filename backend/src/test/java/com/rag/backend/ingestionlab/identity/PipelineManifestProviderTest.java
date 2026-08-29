package com.rag.backend.ingestionlab.identity;

import com.rag.backend.ingestionlab.artifact.ParseSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PipelineManifestProviderTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(PipelineManifestProvider.class)
                    .withPropertyValues(
                            "embedding.model=test-embedding",
                            "milvus.embedding-dimension=1024");

    @Test
    void defaultProfilePreservesExistingManifest() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            PipelineManifest actual = context.getBean(
                    PipelineManifestProvider.class).current();
            PipelineManifest expected = new PipelineManifest(
                    "project-parser-factory",
                    "1",
                    ParseSnapshot.CURRENT_SCHEMA_VERSION,
                    "fixed-window",
                    800,
                    120,
                    1,
                    "test-embedding",
                    1024,
                    "ingestion-v2",
                    "verification-v1");

            assertEquals(expected, actual);
            assertEquals(expected.fingerprint(), actual.fingerprint());
        });
    }

    @Test
    void configuredChunkProfileIsBoundIntoPipelineFingerprint() {
        contextRunner
                .withPropertyValues(
                        "ingestion.chunk.fixed-window.size=512",
                        "ingestion.chunk.fixed-window.overlap=64")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    PipelineManifest configured = context.getBean(
                            PipelineManifestProvider.class).current();
                    PipelineManifest baseline = manifestFor(800, 120);

                    assertEquals(512, configured.chunkSize());
                    assertEquals(64, configured.chunkOverlap());
                    assertNotEquals(
                            baseline.fingerprint(), configured.fingerprint());
                });
    }

    @Test
    void differentProfilesProduceDifferentFingerprints() {
        PipelineManifest baseline = manifestFor(800, 120);
        PipelineManifest candidate = manifestFor(512, 64);

        assertNotEquals(baseline.fingerprint(), candidate.fingerprint());
    }

    @Test
    void invalidProfilePreventsProviderStartup() {
        contextRunner
                .withPropertyValues(
                        "ingestion.chunk.fixed-window.size=120",
                        "ingestion.chunk.fixed-window.overlap=120")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private PipelineManifest manifestFor(int size, int overlap) {
        return new PipelineManifestProvider(
                "test-embedding", 1024, size, overlap).current();
    }
}
