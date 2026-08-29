package com.rag.backend.agent.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceDecisionConfigurationTest {

    @Test
    void policyDefaultsOnButEveryUncalibratedScoreThresholdDefaultsOff()
            throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (var source : loader.load(
                "application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        EvidenceDecisionProperties properties = Binder.get(environment)
                .bind("rag.evidence-decision",
                        Bindable.of(EvidenceDecisionProperties.class))
                .get();

        assertTrue(properties.isEnabled());
        for (EvidenceScoreKind kind : EvidenceScoreKind.values()) {
            assertFalse(properties.threshold(kind).isEnabled(),
                    () -> kind + " must stay disabled before Dev calibration");
        }
    }

    @Test
    void enabledThresholdWithoutCalibrationProvenanceFailsClosed() {
        EvidenceDecisionProperties properties =
                new EvidenceDecisionProperties();
        properties.getThresholds().getLocalRerank().setEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> properties.threshold(EvidenceScoreKind.LOCAL_RERANK));
    }
}
