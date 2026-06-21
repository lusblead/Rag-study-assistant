package com.rag.backend.agent.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnExpression("'${rerank.provider:local}' == 'siliconflow'")
public class SiliconFlowKnowledgeReranker implements KnowledgeReranker {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LocalLexicalKnowledgeReranker fallbackReranker;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean failOpen;
    private final int maxChunksPerDoc;
    private final int overlapTokens;
    private final Duration timeout;

    public SiliconFlowKnowledgeReranker(ObjectMapper objectMapper,
                                        @Value("${rerank.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
                                        @Value("${rerank.api-key:${SILICONFLOW_API_KEY:}}") String apiKey,
                                        @Value("${rerank.model:BAAI/bge-reranker-v2-m3}") String model,
                                        @Value("${rerank.fail-open:true}") boolean failOpen,
                                        @Value("${rerank.max-chunks-per-doc:1024}") int maxChunksPerDoc,
                                        @Value("${rerank.overlap-tokens:50}") int overlapTokens,
                                        @Value("${rerank.timeout-seconds:60}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.fallbackReranker = new LocalLexicalKnowledgeReranker();
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.failOpen = failOpen;
        this.maxChunksPerDoc = maxChunksPerDoc;
        this.overlapTokens = overlapTokens;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        try {
            ensureApiKey();
            String body = objectMapper.writeValueAsString(buildRequest(query, chunks, topK));
            HttpRequest request = HttpRequest.newBuilder(rerankEndpoint())
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new BizException(500, "Rerank request failed, status=" + response.statusCode() + ", body=" + response.body());
            }
            return parseResponse(response.body(), chunks, topK);
        } catch (Exception e) {
            if (failOpen) {
                return fallbackReranker.rerank(query, chunks, topK);
            }
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(500, "Rerank request failed: " + e.getMessage());
        }
    }

    private ObjectNode buildRequest(String query, List<RetrievedChunk> chunks, int topK) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("query", query == null || query.isBlank() ? "empty" : query);
        root.put("return_documents", false);
        root.put("top_n", Math.min(topK, chunks.size()));
        root.put("max_chunks_per_doc", maxChunksPerDoc);
        root.put("overlap_tokens", overlapTokens);

        ArrayNode documents = root.putArray("documents");
        for (RetrievedChunk chunk : chunks) {
            documents.add(chunk.content());
        }
        return root;
    }

    private List<RetrievedChunk> parseResponse(String body, List<RetrievedChunk> chunks, int topK) throws IOException {
        JsonNode results = objectMapper.readTree(body).path("results");
        if (!results.isArray()) {
            throw new BizException(500, "Rerank response does not contain results");
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= chunks.size()) {
                continue;
            }
            double score = item.path("relevance_score").asDouble(chunks.get(index).score() == null ? 0.0 : chunks.get(index).score());
            reranked.add(chunks.get(index).withScore(score));
        }

        return reranked.stream()
                .sorted(Comparator.comparing(RetrievedChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private URI rerankEndpoint() {
        return URI.create(baseUrl + "/rerank");
    }

    private void ensureApiKey() {
        if (apiKey.isBlank()) {
            throw new BizException(500, "Rerank API key is empty. Set RERANK_API_KEY or SILICONFLOW_API_KEY.");
        }
    }

    private String trimTrailingSlash(String value) {
        String text = value == null || value.isBlank() ? "https://api.siliconflow.cn/v1" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
