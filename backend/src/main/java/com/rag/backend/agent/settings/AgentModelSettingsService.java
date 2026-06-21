package com.rag.backend.agent.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.common.BizException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class AgentModelSettingsService {
    private final AgentModelSettingsMapper mapper;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration testTimeout;
    private volatile AgentModelSettings cache;

    public AgentModelSettingsService(AgentModelSettingsMapper mapper,
                                     Environment environment,
                                     ObjectMapper objectMapper,
                                     @Value("${model-test.timeout-seconds:30}") long testTimeoutSeconds) {
        this.mapper = mapper;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.testTimeout = Duration.ofSeconds(testTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.testTimeout)
                .build();
    }

    @PostConstruct
    public void init() {
        loadOrCreate();
    }

    public AgentModelSettings currentSettings() {
        AgentModelSettings current = cache;
        if (current != null) {
            return withApiKeyFallbacks(current);
        }
        synchronized (this) {
            return withApiKeyFallbacks(loadOrCreate());
        }
    }

    public AgentModelSettingsResponse currentResponse() {
        return toResponse(currentSettings());
    }

    public synchronized AgentModelSettingsResponse update(AgentModelSettingsRequest request) {
        if (request == null) {
            throw new BizException("Settings payload is required");
        }

        AgentModelSettings next = copyOf(loadOrCreate());
        next.setLlmProvider(required(request.getLlmProvider(), "LLM provider"));
        next.setLlmBaseUrl(trimTrailingSlash(required(request.getLlmBaseUrl(), "LLM base URL")));
        next.setLlmModel(required(request.getLlmModel(), "LLM model"));
        next.setEmbeddingProvider(required(request.getEmbeddingProvider(), "Embedding provider"));
        next.setEmbeddingBaseUrl(trimTrailingSlash(required(request.getEmbeddingBaseUrl(), "Embedding base URL")));
        next.setEmbeddingModel(required(request.getEmbeddingModel(), "Embedding model"));

        if (Boolean.TRUE.equals(request.getClearLlmApiKey())) {
            next.setLlmApiKey("");
        } else if (hasText(request.getLlmApiKey())) {
            next.setLlmApiKey(request.getLlmApiKey().trim());
        }

        if (Boolean.TRUE.equals(request.getClearEmbeddingApiKey())) {
            next.setEmbeddingApiKey("");
        } else if (hasText(request.getEmbeddingApiKey())) {
            next.setEmbeddingApiKey(request.getEmbeddingApiKey().trim());
        }

        mapper.upsert(next);
        cache = mapper.selectCurrent();
        return toResponse(withApiKeyFallbacks(cache));
    }

    public AgentModelSettingsTestResponse test(AgentModelSettingsTestRequest request) {
        if (request == null) {
            throw new BizException("Settings payload is required");
        }

        String target = required(request.getTarget(), "Test target").toLowerCase();
        AgentModelSettings settings = settingsFromRequest(request);
        long started = System.nanoTime();
        AgentModelSettingsTestResponse response = new AgentModelSettingsTestResponse();
        response.setTarget(target);

        try {
            if ("llm".equals(target)) {
                testLlm(settings, response);
            } else if ("embedding".equals(target)) {
                testEmbedding(settings, response);
            } else {
                throw new BizException("Unsupported test target: " + target);
            }
            response.setSuccess(true);
        } catch (BizException e) {
            response.setSuccess(false);
            response.setMessage(e.getMessage());
        } catch (Exception e) {
            response.setSuccess(false);
            response.setMessage(e.getMessage());
        } finally {
            response.setLatencyMs(Duration.ofNanos(System.nanoTime() - started).toMillis());
        }

        return response;
    }

    private AgentModelSettings loadOrCreate() {
        AgentModelSettings stored = mapper.selectCurrent();
        if (stored != null) {
            cache = stored;
            return stored;
        }

        AgentModelSettings defaults = defaultSettings();
        mapper.upsert(defaults);
        cache = mapper.selectCurrent();
        return cache;
    }

    private AgentModelSettings defaultSettings() {
        AgentModelSettings settings = new AgentModelSettings();
        settings.setId(1L);
        settings.setLlmProvider(property("llm.provider", "deepseek"));
        settings.setLlmBaseUrl(trimTrailingSlash(property("llm.base-url", "https://api.deepseek.com")));
        settings.setLlmModel(property("llm.model", "deepseek-v4-pro"));
        settings.setLlmApiKey(firstNonBlank(property("llm.api-key", ""), property("DEEPSEEK_API_KEY", "")));
        settings.setEmbeddingProvider(property("embedding.provider", "openai-compatible"));
        settings.setEmbeddingBaseUrl(trimTrailingSlash(property("embedding.base-url", "https://api.siliconflow.com/v1")));
        settings.setEmbeddingModel(property("embedding.model", "BAAI/bge-m3"));
        settings.setEmbeddingApiKey(firstNonBlank(property("embedding.api-key", ""), property("SILICONFLOW_API_KEY", "")));
        return settings;
    }

    private AgentModelSettings settingsFromRequest(AgentModelSettingsRequest request) {
        AgentModelSettings next = currentSettings();
        next.setLlmProvider(required(request.getLlmProvider(), "LLM provider"));
        next.setLlmBaseUrl(trimTrailingSlash(required(request.getLlmBaseUrl(), "LLM base URL")));
        next.setLlmModel(required(request.getLlmModel(), "LLM model"));
        next.setEmbeddingProvider(required(request.getEmbeddingProvider(), "Embedding provider"));
        next.setEmbeddingBaseUrl(trimTrailingSlash(required(request.getEmbeddingBaseUrl(), "Embedding base URL")));
        next.setEmbeddingModel(required(request.getEmbeddingModel(), "Embedding model"));

        if (Boolean.TRUE.equals(request.getClearLlmApiKey())) {
            next.setLlmApiKey("");
        } else if (hasText(request.getLlmApiKey())) {
            next.setLlmApiKey(request.getLlmApiKey().trim());
        }

        if (Boolean.TRUE.equals(request.getClearEmbeddingApiKey())) {
            next.setEmbeddingApiKey("");
        } else if (hasText(request.getEmbeddingApiKey())) {
            next.setEmbeddingApiKey(request.getEmbeddingApiKey().trim());
        }

        return next;
    }

    private void testLlm(AgentModelSettings settings, AgentModelSettingsTestResponse response)
            throws IOException, InterruptedException {
        if ("local".equalsIgnoreCase(settings.getLlmProvider())) {
            response.setMessage("Local LLM provider is available.");
            return;
        }
        if (!hasText(settings.getLlmApiKey())) {
            throw new BizException("LLM API key is empty.");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", settings.getLlmModel());
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", "Reply with OK.");

        HttpResponse<String> httpResponse = sendJson(
                trimTrailingSlash(settings.getLlmBaseUrl()) + "/chat/completions",
                settings.getLlmApiKey(),
                objectMapper.writeValueAsString(root)
        );
        response.setStatusCode(httpResponse.statusCode());
        ensureHttpSuccess(httpResponse.statusCode(), httpResponse.body(), "LLM");

        JsonNode content = objectMapper.readTree(httpResponse.body())
                .path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new BizException("LLM response does not contain message content.");
        }
        response.setMessage("LLM API key is valid. Response: " + abbreviate(content.asText(), 120));
    }

    private void testEmbedding(AgentModelSettings settings, AgentModelSettingsTestResponse response)
            throws IOException, InterruptedException {
        if ("local".equalsIgnoreCase(settings.getEmbeddingProvider())) {
            response.setMessage("Local embedding provider is available.");
            return;
        }
        if (!hasText(settings.getEmbeddingApiKey())) {
            throw new BizException("Embedding API key is empty.");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", settings.getEmbeddingModel());
        root.put("input", "test embedding connectivity");
        root.put("encoding_format", "float");

        HttpResponse<String> httpResponse = sendJson(
                trimTrailingSlash(settings.getEmbeddingBaseUrl()) + "/embeddings",
                settings.getEmbeddingApiKey(),
                objectMapper.writeValueAsString(root)
        );
        response.setStatusCode(httpResponse.statusCode());
        ensureHttpSuccess(httpResponse.statusCode(), httpResponse.body(), "Embedding");

        JsonNode vector = objectMapper.readTree(httpResponse.body())
                .path("data").path(0).path("embedding");
        if (!vector.isArray()) {
            throw new BizException("Embedding response does not contain data[0].embedding.");
        }
        response.setMessage("Embedding API key is valid. Vector dimension: " + vector.size());
    }

    private HttpResponse<String> sendJson(String url, String apiKey, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(testTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void ensureHttpSuccess(int statusCode, String body, String target) {
        if (statusCode >= 400) {
            throw new BizException(target + " test failed, status=" + statusCode + ", body=" + abbreviate(body, 500));
        }
    }

    private AgentModelSettingsResponse toResponse(AgentModelSettings settings) {
        AgentModelSettingsResponse response = new AgentModelSettingsResponse();
        response.setLlmProvider(settings.getLlmProvider());
        response.setLlmBaseUrl(settings.getLlmBaseUrl());
        response.setLlmModel(settings.getLlmModel());
        response.setLlmApiKeySet(hasText(settings.getLlmApiKey()));
        response.setEmbeddingProvider(settings.getEmbeddingProvider());
        response.setEmbeddingBaseUrl(settings.getEmbeddingBaseUrl());
        response.setEmbeddingModel(settings.getEmbeddingModel());
        response.setEmbeddingApiKeySet(hasText(settings.getEmbeddingApiKey()));
        return response;
    }

    private AgentModelSettings copyOf(AgentModelSettings source) {
        AgentModelSettings copy = new AgentModelSettings();
        copy.setId(source.getId());
        copy.setLlmProvider(source.getLlmProvider());
        copy.setLlmBaseUrl(source.getLlmBaseUrl());
        copy.setLlmModel(source.getLlmModel());
        copy.setLlmApiKey(source.getLlmApiKey());
        copy.setEmbeddingProvider(source.getEmbeddingProvider());
        copy.setEmbeddingBaseUrl(source.getEmbeddingBaseUrl());
        copy.setEmbeddingModel(source.getEmbeddingModel());
        copy.setEmbeddingApiKey(source.getEmbeddingApiKey());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private AgentModelSettings withApiKeyFallbacks(AgentModelSettings source) {
        AgentModelSettings copy = copyOf(source);
        if (!hasText(copy.getLlmApiKey())) {
            copy.setLlmApiKey(firstNonBlank(property("llm.api-key", ""), property("DEEPSEEK_API_KEY", "")));
        }
        if (!hasText(copy.getEmbeddingApiKey())) {
            copy.setEmbeddingApiKey(firstNonBlank(property("embedding.api-key", ""), property("SILICONFLOW_API_KEY", "")));
        }
        return copy;
    }

    private String property(String key, String fallback) {
        String value = environment.getProperty(key);
        return hasText(value) ? value.trim() : fallback;
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first.trim() : hasText(second) ? second.trim() : "";
    }

    private String required(String value, String field) {
        if (!hasText(value)) {
            throw new BizException(field + " cannot be empty");
        }
        return value.trim();
    }

    private String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
