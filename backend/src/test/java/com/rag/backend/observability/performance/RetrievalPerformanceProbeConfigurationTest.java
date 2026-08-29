package com.rag.backend.observability.performance;

import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RetrievalPerformanceProbeConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(
                            KnowledgeRetriever.class,
                            () -> mock(KnowledgeRetriever.class))
                    .withUserConfiguration(ProbeImportConfiguration.class);

    @Test
    void controllerIsAbsentUnlessProbeIsExplicitlyEnabled() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(
                        RetrievalPerformanceProbeController.class));

        contextRunner
                .withPropertyValues(
                        "rag.performance.probe.enabled=true",
                        "rag.performance.probe.token=test-token")
                .run(context -> assertThat(context)
                        .hasSingleBean(
                                RetrievalPerformanceProbeController.class));
    }

    @Test
    void performanceProfileIsLoopbackOnlyAndFailClosed()
            throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(
                        "performance",
                        new ClassPathResource(
                                "application-performance.yml"));

        assertThat(property(sources, "server.address"))
                .isEqualTo("127.0.0.1");
        assertThat(property(sources, "management.server.address"))
                .isEqualTo("127.0.0.1");
        assertThat(property(sources, "management.server.port"))
                .isEqualTo("${RAG_PERFORMANCE_MANAGEMENT_PORT:8081}");
        assertThat(property(
                sources,
                "management.metrics.distribution.percentiles.rag.performance.span.duration"))
                .isEqualTo("0.5,0.95,0.99");
        Set<String> exposed = Arrays.stream(
                        property(
                                sources,
                                "management.endpoints.web.exposure.include")
                                .toString()
                                .split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        assertThat(exposed).containsExactlyInAnyOrder(
                "health", "info", "metrics");
        assertThat(property(sources, "management.info.env.enabled"))
                .isEqualTo(true);
        assertThat(property(
                sources,
                "info.rag.performance.runtimeBuildFingerprintSha256"))
                .isEqualTo(
                        "${RAG_PERFORMANCE_RUNTIME_BUILD_FINGERPRINT_SHA256:}");
        assertThat(property(sources, "rag.performance.probe.enabled"))
                .isEqualTo(false);
        assertThat(property(sources, "rag.performance.probe.token"))
                .isEqualTo("${RAG_PERFORMANCE_TOKEN:}");
    }

    private static Object property(
            List<PropertySource<?>> sources,
            String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RetrievalPerformanceProbeController.class)
    static class ProbeImportConfiguration {
    }
}
