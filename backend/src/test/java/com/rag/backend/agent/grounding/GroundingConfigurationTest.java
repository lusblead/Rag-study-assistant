package com.rag.backend.agent.grounding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundingConfigurationTest {

    @Test
    void strictGroundingDefaultsOffUntilHumanCalibrationExists()
            throws Exception {
        assertFalse(load(Map.of()).isEnabled());
        assertTrue(load(Map.of("RAG_GROUNDING_ENABLED", "true"))
                .isEnabled());
    }

    private GroundingProperties load(Map<String, Object> overrides)
            throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("test-overrides", overrides));
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (var source : loader.load(
                "application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment)
                .bind("rag.grounding", Bindable.of(GroundingProperties.class))
                .get();
    }
}
