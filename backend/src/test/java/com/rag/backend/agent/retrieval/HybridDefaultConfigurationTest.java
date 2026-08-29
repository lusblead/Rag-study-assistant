package com.rag.backend.agent.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridDefaultConfigurationTest {

    @Test
    void defaultsHybridOnWithoutManualEnableAndRetainsExplicitRollback()
            throws IOException {
        assertEquals(Boolean.TRUE, resolveHybridEnabled(Map.of()));
        assertEquals(Boolean.FALSE, resolveHybridEnabled(
                Map.of("RAG_HYBRID_ENABLED", "false")));
        assertEquals("${rag.hybrid.enabled:true}", constructorFallback());
    }

    private Boolean resolveHybridEnabled(Map<String, Object> overrides)
            throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        if (!overrides.isEmpty()) {
            sources.addFirst(new MapPropertySource("test-overrides", overrides));
        }

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (var source : loader.load(
                "application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }

        return new PropertySourcesPropertyResolver(sources)
                .getProperty("rag.hybrid.enabled", Boolean.class);
    }

    private String constructorFallback() {
        return Arrays.stream(MilvusKnowledgeRetriever.class
                        .getDeclaredConstructors())
                .filter(constructor -> constructor
                        .isAnnotationPresent(Autowired.class))
                .flatMap(constructor -> Arrays.stream(
                        constructor.getParameters()))
                .map(Parameter::getAnnotations)
                .flatMap(Arrays::stream)
                .filter(Value.class::isInstance)
                .map(Value.class::cast)
                .map(Value::value)
                .filter(value -> value.startsWith("${rag.hybrid.enabled:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "production constructor must declare rag.hybrid.enabled"));
    }
}
