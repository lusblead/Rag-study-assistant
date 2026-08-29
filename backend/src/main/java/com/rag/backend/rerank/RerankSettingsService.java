package com.rag.backend.rerank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.agent.settings.AgentModelSettingsService;
import com.rag.backend.common.BizException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.rag.backend.agent.rerank.RerankExecutionResult.FailureType;

@Service
public class RerankSettingsService {
    public static final String DEFAULT_MODEL = "Pro/BAAI/bge-reranker-v2-m3";
    private static final Set<String> PROVIDERS = Set.of("none", "local", "siliconflow");

    private final RerankSettingsMapper mapper;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final AgentModelSettingsService agentModelSettingsService;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxChunksPerDoc;
    private final int overlapTokens;
    private volatile RerankSettings cache;

    @Autowired
    public RerankSettingsService(RerankSettingsMapper mapper,
                                 Environment environment,
                                 ObjectMapper objectMapper,
                                 AgentModelSettingsService agentModelSettingsService) {
        this.mapper = mapper;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.agentModelSettingsService = agentModelSettingsService;
        long timeoutSeconds = environment.getProperty(
                "rerank.timeout-seconds", Long.class, 60L);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "rerank.timeout-seconds must be > 0");
        }
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
        this.maxChunksPerDoc = positiveProperty(
                environment, "rerank.max-chunks-per-doc", 1024);
        this.overlapTokens = positiveOrZeroProperty(
                environment, "rerank.overlap-tokens", 50);
    }

    RerankSettingsService(
            RerankSettingsMapper mapper,
            Environment environment,
            ObjectMapper objectMapper,
            AgentModelSettingsService agentModelSettingsService,
            HttpClient httpClient,
            Duration requestTimeout) {
        this.mapper = mapper;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.agentModelSettingsService = agentModelSettingsService;
        this.httpClient = httpClient;
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "requestTimeout must be > 0");
        }
        this.requestTimeout = requestTimeout;
        this.maxChunksPerDoc = 1024;
        this.overlapTokens = 50;
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
        if (chunks == null || settings == null) {
            throw new IllegalArgumentException(
                    "chunks and settings must not be null");
        }
        if (chunks.isEmpty()) {
            return List.of();
        }
        if (topK <= 0) {
            throw technical(FailureType.CONFIGURATION,
                    "Remote rerank topK must be > 0");
        }
        if (!hasText(settings.getApiKey())) {
            throw technical(FailureType.CONFIGURATION,
                    "Rerank API Key is not configured");
        }
        try {
            int requestedCount = Math.min(topK, chunks.size());
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", settings.getModel());
            payload.put("query", hasText(query) ? query : "empty");
            payload.put("top_n", requestedCount);
            payload.put("return_documents", false);
            payload.put("max_chunks_per_doc", maxChunksPerDoc);
            payload.put("overlap_tokens", overlapTokens);
            ArrayNode documents = payload.putArray("documents");
            chunks.forEach(chunk -> documents.add(chunk.content()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimUrl(settings.getBaseUrl()) + "/rerank"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + settings.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw technical(FailureType.HTTP_ERROR,
                        "Rerank provider returned HTTP "
                                + response.statusCode());
            }
            return parseRemoteResponse(
                    response.body(), chunks, requestedCount);
        } catch (RerankExecutionResult.TechnicalFailure e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw technical(FailureType.EXECUTION_ERROR,
                    "Rerank request serialization failed");
        } catch (HttpTimeoutException e) {
            throw technical(FailureType.TIMEOUT,
                    "Rerank provider request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw technical(FailureType.INTERRUPTED,
                    "Rerank provider request was interrupted");
        } catch (java.io.IOException e) {
            throw technical(FailureType.NETWORK_ERROR,
                    "Rerank provider request failed");
        } catch (IllegalArgumentException e) {
            throw technical(FailureType.CONFIGURATION,
                    "Rerank provider configuration is invalid");
        }
    }

    private List<RetrievedChunk> parseRemoteResponse(
            String body,
            List<RetrievedChunk> chunks,
            int requestedCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException failure) {
            throw technical(FailureType.INVALID_RESPONSE,
                    "Rerank provider returned invalid JSON");
        }
        JsonNode results = root == null ? null : root.get("results");
        if (results == null || !results.isArray()
                || results.size() != requestedCount) {
            throw technical(FailureType.INVALID_RESPONSE,
                    "Rerank provider returned an incomplete result set");
        }

        Set<Integer> seenIndexes = new HashSet<>();
        List<RetrievedChunk> reranked = new ArrayList<>(requestedCount);
        for (JsonNode item : results) {
            JsonNode indexNode = item == null ? null : item.get("index");
            JsonNode scoreNode = item == null
                    ? null
                    : item.get("relevance_score");
            if (indexNode == null || !indexNode.canConvertToInt()
                    || scoreNode == null || !scoreNode.isNumber()) {
                throw technical(FailureType.INVALID_RESPONSE,
                        "Rerank provider returned an invalid result item");
            }
            int index = indexNode.intValue();
            double score = scoreNode.doubleValue();
            if (index < 0 || index >= chunks.size()
                    || !seenIndexes.add(index)
                    || !Double.isFinite(score)) {
                throw technical(FailureType.INVALID_RESPONSE,
                        "Rerank provider returned invalid indexes or scores");
            }
            reranked.add(chunks.get(index).withRerankScore(score));
        }

        return reranked.stream()
                .sorted(Comparator
                        .comparing(RetrievedChunk::score,
                                Comparator.reverseOrder())
                        .thenComparing(RetrievedChunk::chunkId,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder())))
                .toList();
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
    private RerankExecutionResult.TechnicalFailure technical(
            FailureType failureType,
            String safeMessage) {
        return new RerankExecutionResult.TechnicalFailure(
                failureType, safeMessage);
    }

    private static int positiveProperty(
            Environment environment,
            String name,
            int fallback) {
        int value = environment.getProperty(name, Integer.class, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    private static int positiveOrZeroProperty(
            Environment environment,
            String name,
            int fallback) {
        int value = environment.getProperty(name, Integer.class, fallback);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
    }
}
