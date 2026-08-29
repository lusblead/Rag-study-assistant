package com.rag.backend.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsCoreArchitectureTest {
    private static final List<String> RUNNERS = List.of(
            "LocalObsidianRetrievalEvalTest.java",
            "RetrievalFixtureSmokeTest.java",
            "PublicRetrievalEvalTest.java",
            "RagRetrievalSmokeEvalTest.java");
    private static final Pattern DUPLICATE_HELPER = Pattern.compile(
            "(?m)\\bprivate\\s+(?:static\\s+)?double\\s+"
                    + "(?:recallAt\\w*|reciprocalRank|ndcgAt\\w*|log2)\\s*\\(");

    @Test
    void retrievalRunnersUseMetricsCoreWithoutCanonicalFormulaCopies()
            throws IOException {
        Path sourceDirectory = sourceDirectory();
        for (String runner : RUNNERS) {
            String source = Files.readString(sourceDirectory.resolve(runner));
            assertTrue(source.contains("RetrievalMetricsCalculator"),
                    runner + " must reference RetrievalMetricsCalculator");
            assertTrue(source.contains(".evaluateCase("),
                    runner + " must evaluate cases through Metrics Core");
            assertTrue(source.contains(".summarizeGroundTruth("),
                    runner + " must aggregate cases through Metrics Core");
            assertFalse(DUPLICATE_HELPER.matcher(source).find(),
                    runner + " must not declare duplicate canonical helpers");
            assertFalse(source.contains("Math.log("),
                    runner + " must not inline the nDCG logarithm formula");
        }
    }

    private Path sourceDirectory() {
        Path fromRepositoryRoot = Path.of(
                "backend/src/test/java/com/rag/backend/agent/retrieval/eval");
        if (Files.isDirectory(fromRepositoryRoot)) {
            return fromRepositoryRoot;
        }
        Path fromBackendModule = Path.of(
                "src/test/java/com/rag/backend/agent/retrieval/eval");
        assertTrue(Files.isDirectory(fromBackendModule),
                "cannot locate retrieval eval runner sources");
        return fromBackendModule;
    }
}
