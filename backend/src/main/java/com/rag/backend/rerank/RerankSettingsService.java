package com.rag.backend.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.agent.settings.AgentModelSettingsService;
import com.rag.backend.common.BizException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class RerankSettingsService {
    public static final String DEFAULT_MODEL = "Pro/BAAI/bge-reranker-v2-m3";
    private static final Set<String> PROVIDERS = Set.of("none", "local", "siliconflow");

    private final RerankSettingsMapper mapper;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final AgentModelSettingsService agentModelSettingsService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private volatile RerankSettings cache;

    public RerankSettingsService(RerankSettingsMapper mapper,
                                 Environment environment,
                                 ObjectMapper objectMapper,
                                 AgentModelSettingsService agentModelSettingsService) {
        this.mapper = mapper;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.agentModelSettingsService = agentModelSettingsService;
    }

    @PostConstruct
    public void init() { loadOrCreate(); }

    public RerankSettings current() {
        RerankSettings value = cache;
        return value == null ? loadOrCreate() : copyWithFallbackKey(value);
    }

    public RerankSettingsResponse response() { return toResponse(current()); }

    public synchronized RerankSettingsResponse update(RerankSettingsRequest request) {
        if (request == null) throw new BizException(400, "rerank 设置不能为空");
        String provider = normalizeProvider(request.getProvider());
        RerankSettings next = copy(loadOrCreate());
        next.setProvider(provider);
        next.setBaseUrl(trimUrl(hasText(request.getBaseUrl()) ? request.getBaseUrl() : "https://api.siliconflow.cn/v1"));
        next.setModel(hasText(request.getModel()) ? request.getModel().trim() : DEFAULT_MODEL);
        next.setFailOpen(request.getFailOpen() == null || request.getFailOpen());
        if (Boolean.TRUE.equals(request.getClearApiKey())) next.setApiKey("");
        else if (hasText(request.getApiKey())) next.setApiKey(request.getApiKey().trim());
        mapper.upsert(next);
        cache = mapper.selectCurrent();
        return toResponse(current());
    }

    public RerankSettingsResponse test(RerankSettingsRequest request) {
        RerankSettings settings = fromRequestForTest(request);
        if ("none".equals(settings.getProvider()) || "local".equals(settings.getProvider())) {
            return toResponse(settings);
        }
        rerankRemote("测试查询", List.of(
                new RetrievedChunk(1L, 1L, "相关", "这是与测试查询相关的文档", 0.5),
                new RetrievedChunk(2L, 1L, "无关", "天气晴朗", 0.4)
        ), 2, settings);
        return toResponse(settings);
    }

    public List<RetrievedChunk> rerankRemote(String query, List<RetrievedChunk> chunks, int topK, RerankSettings settings) {
        if (!hasText(settings.getApiKey())) {
            throw new BizException(500, "Rerank API Key 为空");
        }
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", settings.getModel());
            payload.put("query", hasText(query) ? query : "empty");
            payload.put("top_n", Math.min(topK, chunks.size()));
            payload.put("return_documents", false);
            payload.put("max_chunks_per_doc", 1024);
            payload.put("overlap_tokens", 50);
            ArrayNode documents = payload.putArray("documents");
            chunks.forEach(chunk -> documents.add(chunk.content()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimUrl(settings.getBaseUrl()) + "/rerank"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + settings.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new BizException(500, "Rerank 请求失败，HTTP " + response.statusCode() + ": " + abbreviate(response.body(), 300));
            }
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (!results.isArray()) throw new BizException(500, "Rerank 响应缺少 results");
            List<RetrievedChunk> reranked = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < chunks.size()) {
                    reranked.add(chunks.get(index).withScore(item.path("relevance_score").asDouble(0)));
                }
            }
            return reranked.stream().sorted(Comparator.comparing(RetrievedChunk::score).reversed()).limit(topK).toList();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "Rerank 请求失败: " + e.getMessage());
        }
    }

    private synchronized RerankSettings loadOrCreate() {
        RerankSettings stored = mapper.selectCurrent();
        if (stored != null) { cache = stored; return stored; }
        RerankSettings defaults = new RerankSettings();
        defaults.setId(1L);
        defaults.setProvider(normalizeProvider(environment.getProperty("rerank.provider", "local")));
        defaults.setBaseUrl(trimUrl(environment.getProperty("rerank.base-url", "https://api.siliconflow.cn/v1")));
        defaults.setModel(environment.getProperty("rerank.model", DEFAULT_MODEL));
        defaults.setApiKey(firstNonBlank(environment.getProperty("rerank.api-key"), environment.getProperty("SILICONFLOW_API_KEY")));
        defaults.setFailOpen(Boolean.parseBoolean(environment.getProperty("rerank.fail-open", "true")));
        mapper.upsert(defaults);
        cache = mapper.selectCurrent();
        return cache;
    }

    private RerankSettings fromRequestForTest(RerankSettingsRequest request) {
        RerankSettings value = copy(current());
        if (request == null) return value;
        value.setProvider(normalizeProvider(request.getProvider()));
        if (hasText(request.getBaseUrl())) value.setBaseUrl(trimUrl(request.getBaseUrl()));
        if (hasText(request.getModel())) value.setModel(request.getModel().trim());
        if (hasText(request.getApiKey())) value.setApiKey(request.getApiKey().trim());
        if (Boolean.TRUE.equals(request.getClearApiKey())) value.setApiKey("");
        return copyWithFallbackKey(value);
    }

    private RerankSettings copyWithFallbackKey(RerankSettings source) {
        RerankSettings copy = copy(source);
        if (!hasText(copy.getApiKey())) {
            String environmentKey = firstNonBlank(environment.getProperty("rerank.api-key"), environment.getProperty("SILICONFLOW_API_KEY"));
            copy.setApiKey(hasText(environmentKey)
                    ? environmentKey
                    : agentModelSettingsService.currentSettings().getEmbeddingApiKey());
        }
        return copy;
    }

    private RerankSettings copy(RerankSettings source) {
        RerankSettings copy = new RerankSettings();
        copy.setId(source.getId()); copy.setProvider(source.getProvider()); copy.setBaseUrl(source.getBaseUrl());
        copy.setModel(source.getModel()); copy.setApiKey(source.getApiKey()); copy.setFailOpen(source.getFailOpen());
        return copy;
    }

    private RerankSettingsResponse toResponse(RerankSettings settings) {
        RerankSettingsResponse response = new RerankSettingsResponse();
        response.setProvider(settings.getProvider()); response.setBaseUrl(settings.getBaseUrl());
        response.setModel(settings.getModel()); response.setApiKeySet(hasText(settings.getApiKey()));
        response.setFailOpen(Boolean.TRUE.equals(settings.getFailOpen()));
        return response;
    }

    private String normalizeProvider(String value) {
        String provider = hasText(value) ? value.trim().toLowerCase() : "local";
        if (!PROVIDERS.contains(provider)) throw new BizException(400, "不支持的 rerank 模式: " + provider);
        return provider;
    }
    private String trimUrl(String value) {
        String text = hasText(value) ? value.trim() : "https://api.siliconflow.cn/v1";
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }
    private String firstNonBlank(String first, String second) { return hasText(first) ? first.trim() : hasText(second) ? second.trim() : ""; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String abbreviate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max) + "…"; }
}
