package com.rag.backend.agent.evaluation.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Explicitly enabled formal Dev sweep. It performs no network or service calls. */
class AnswerabilityDecisionSweepHarnessTest {

    @Test
    @EnabledIfSystemProperty(named = "answerability.eval.enabled", matches = "true")
    void runsFormalHumanReviewedDevelopmentSweep() throws Exception {
        Path datasetRoot = Path.of(requiredProperty("answerability.eval.datasetDir"));
        Path output = Path.of(requiredProperty("answerability.eval.output"))
                .toAbsolutePath()
                .normalize();
        ThresholdDecisionPredictor predictor = predictor(
                requiredProperty("answerability.eval.predictorClass"));

        ObjectMapper mapper = new ObjectMapper();
        FormalDecisionDataset dataset =
                new FormalDecisionDatasetLoader(mapper).load(datasetRoot);
        ThresholdSweepReport report =
                new DeterministicThresholdSweep().run(dataset, predictor);

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream stream = Files.newOutputStream(
                output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(stream, report);
        }
    }

    private ThresholdDecisionPredictor predictor(String className) throws Exception {
        Class<?> type = Class.forName(className);
        if (!ThresholdDecisionPredictor.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                    className + " must implement " + ThresholdDecisionPredictor.class.getName());
        }
        return (ThresholdDecisionPredictor) type.getDeclaredConstructor().newInstance();
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing required system property: " + name);
        }
        return value;
    }
}
