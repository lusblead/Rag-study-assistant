package com.rag.backend.agent.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.settings.AgentModelSettings;
import com.rag.backend.agent.settings.AgentModelSettingsService;
import com.rag.backend.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnExpression("'${agent.mock:false}' == 'false'")
// 调用 OpenAI-compatible Embedding 接口或本地向量实现生成文本向量。
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AgentModelSettingsService settingsService;
    private final int expectedDimension;
    private final Duration timeout;

    public OpenAiCompatibleEmbeddingClient(ObjectMapper objectMapper,
                                           AgentModelSettingsService settingsService,
                                           @Value("${milvus.embedding-dimension:1024}") int expectedDimension,
                                           @Value("${embedding.timeout-seconds:120}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
        this.expectedDimension = expectedDimension;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public List<Double> embed(String text) {
        AgentModelSettings settings = settingsService.currentSettings();
        if (isLocal(settings.getEmbeddingProvider())) {
            return localHashEmbedding(text);
        }

        ensureApiKey(settings);
        try {
            String body = objectMapper.writeValueAsString(buildRequest(text, settings));
            HttpRequest request = HttpRequest.newBuilder(embeddingEndpoint(settings))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.getEmbeddingApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new BizException(500, "Embedding request failed, status=" + response.statusCode() + ", body=" + response.body());
            }
            List<Double> embedding = parseEmbedding(response.body());
            if (expectedDimension > 0 && embedding.size() != expectedDimension) {
                throw new BizException(500, "Embedding dimension mismatch. model=" + settings.getEmbeddingModel()
                        + ", actual=" + embedding.size()
                        + ", expected=" + expectedDimension
                        + ". Check EMBEDDING_DIMENSION and Milvus collection config.");
            }
            return embedding;
        } catch (IOException e) {
            throw new BizException(500, "Embedding request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "Embedding request was interrupted");
        }
    }

    private ObjectNode buildRequest(String text, AgentModelSettings settings) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", settings.getEmbeddingModel());
        root.put("input", text == null || text.isBlank() ? "empty" : text);
        root.put("encoding_format", "float");
        return root;
    }

    private List<Double> parseEmbedding(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode vector = root.path("data").path(0).path("embedding");
        if (!vector.isArray()) {
            throw new BizException(500, "Embedding response does not contain data[0].embedding");
        }

        List<Double> values = new ArrayList<>(vector.size());
        for (JsonNode item : vector) {
            values.add(item.asDouble());
        }
        return values;
    }

    private URI embeddingEndpoint(AgentModelSettings settings) {
        return URI.create(trimTrailingSlash(settings.getEmbeddingBaseUrl(), "https://api.siliconflow.com/v1") + "/embeddings");
    }

    private void ensureApiKey(AgentModelSettings settings) {
        if (settings.getEmbeddingApiKey() == null || settings.getEmbeddingApiKey().isBlank()) {
            throw new BizException(500, "Embedding API key is empty. Set it in the frontend settings page.");
        }
    }

    private boolean isLocal(String provider) {
        return "local".equalsIgnoreCase(provider);
    }

    private List<Double> localHashEmbedding(String text) {
        int dimension = expectedDimension > 0 ? expectedDimension : 1024;
        double[] vector = new double[dimension];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String[] tokens = normalized.split("[^\\p{IsHan}a-z0-9]+");

        int tokenCount = 0;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            addToken(vector, token);
            tokenCount++;
        }
        if (tokenCount == 0) {
            addToken(vector, normalized.isBlank() ? "empty" : normalized);
        }

        normalize(vector);
        List<Double> result = new ArrayList<>(dimension);
        for (double value : vector) {
            result.add(value);
        }
        return result;
    }

    private void addToken(double[] vector, String token) {
        byte[] digest = sha256(token);
        int rawIndex = ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
        int index = rawIndex % vector.length;
        double sign = (digest[2] & 1) == 0 ? 1.0 : -1.0;
        vector[index] += sign;
    }

    private void normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm == 0.0) {
            vector[0] = 1.0;
            return;
        }
        double scale = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / scale;
        }
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String trimTrailingSlash(String value, String fallback) {
        String text = value == null || value.isBlank() ? fallback : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
