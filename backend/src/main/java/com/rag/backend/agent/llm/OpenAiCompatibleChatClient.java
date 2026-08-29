package com.rag.backend.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.settings.AgentModelSettings;
import com.rag.backend.agent.settings.AgentModelSettingsService;
import com.rag.backend.common.BizException;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

@Component
@ConditionalOnExpression("'${agent.mock:false}' == 'false'")
// 调用 DeepSeek 或 OpenAI-compatible 聊天补全接口。
public class OpenAiCompatibleChatClient implements ChatClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AgentModelSettingsService settingsService;
    private final String systemPrompt;
    private final double temperature;
    private final boolean thinking;
    private final String reasoningEffort;
    private final Duration timeout;
    private final TraceContextService traces;

    @Autowired
    public OpenAiCompatibleChatClient(ObjectMapper objectMapper,
                                      AgentModelSettingsService settingsService,
                                      @Value("${llm.system-prompt:You are a helpful study assistant.}") String systemPrompt,
                                      @Value("${llm.temperature:0.2}") double temperature,
                                      @Value("${llm.thinking:false}") boolean thinking,
                                      @Value("${llm.reasoning-effort:}") String reasoningEffort,
                                      @Value("${llm.timeout-seconds:120}") long timeoutSeconds,
                                      TraceContextService traces) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .build(),
                objectMapper, settingsService, systemPrompt, temperature,
                thinking, reasoningEffort, timeoutSeconds, traces);
    }

    /** 保留现有测试与手工装配入口。 */
    public OpenAiCompatibleChatClient(ObjectMapper objectMapper,
                                      AgentModelSettingsService settingsService,
                                      String systemPrompt,
                                      double temperature,
                                      boolean thinking,
                                      String reasoningEffort,
                                      long timeoutSeconds) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .build(),
                objectMapper, settingsService, systemPrompt, temperature,
                thinking, reasoningEffort, timeoutSeconds,
                new TraceContextService());
    }

    OpenAiCompatibleChatClient(HttpClient httpClient,
                               ObjectMapper objectMapper,
                               AgentModelSettingsService settingsService,
                               String systemPrompt,
                               double temperature,
                               boolean thinking,
                               String reasoningEffort,
                               long timeoutSeconds) {
        this(httpClient, objectMapper, settingsService, systemPrompt,
                temperature, thinking, reasoningEffort, timeoutSeconds,
                new TraceContextService());
    }

    OpenAiCompatibleChatClient(HttpClient httpClient,
                               ObjectMapper objectMapper,
                               AgentModelSettingsService settingsService,
                               String systemPrompt,
                               double temperature,
                               boolean thinking,
                               String reasoningEffort,
                               long timeoutSeconds,
                               TraceContextService traces) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.thinking = thinking;
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    @Override
    public String call(String prompt) {
        AgentModelSettings settings = settingsService.currentSettings();
        if (isLocal(settings.getLlmProvider())) {
            return localCall(prompt);
        }

        try (TraceSpan span = traces.startSpan("chat.llm.http")) {
            try {
                ensureApiKey(settings);
                String body = objectMapper.writeValueAsString(buildRequest(prompt, false, settings));
                HttpRequest request = baseRequest(chatEndpoint(settings), settings.getLlmApiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                ensureSuccess(response.statusCode());
                String answer = extractMessageContent(response.body());
                span.result("success");
                return answer;
            } catch (IOException e) {
                span.error(e);
                throw new BizException(500, "LLM request failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                span.error(e);
                throw new BizException(500, "LLM request was interrupted");
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    @Override
    public Flux<String> stream(String prompt) {
        AgentModelSettings settings = settingsService.currentSettings();
        if (isLocal(settings.getLlmProvider())) {
            return Flux.just(localCall(prompt));
        }

        TraceCarrier carrier = traces.capture(null);
        return Flux.<String>create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<CompletableFuture<HttpResponse<Stream<String>>>>
                    exchange = new AtomicReference<>();
            AtomicReference<Stream<String>> responseLines =
                    new AtomicReference<>();
            sink.onCancel(() -> abortStream(
                    cancelled, exchange, responseLines));
            try (TraceSpan span = traces.continueOrStart(
                    carrier, "chat.llm.http", carrier.correlationId())) {
                try {
                    ensureApiKey(settings);
                    String body = objectMapper.writeValueAsString(buildRequest(prompt, true, settings));
                    HttpRequest request = baseRequest(chatEndpoint(settings), settings.getLlmApiKey())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    if (cancelled.get() || sink.isCancelled()) {
                        span.result("cancelled");
                        return;
                    }
                    CompletableFuture<HttpResponse<Stream<String>>> pending =
                            httpClient.sendAsync(
                                    request,
                                    HttpResponse.BodyHandlers.ofLines());
                    exchange.set(pending);
                    if (cancelled.get() || sink.isCancelled()) {
                        pending.cancel(true);
                        span.result("cancelled");
                        return;
                    }
                    HttpResponse<Stream<String>> response =
                            awaitStreamingResponse(pending);
                    exchange.compareAndSet(pending, null);
                    Stream<String> bodyLines = response.body();
                    responseLines.set(bodyLines);
                    if (cancelled.get() || sink.isCancelled()) {
                        closeResponseLines(responseLines);
                        span.result("cancelled");
                        return;
                    }
                    try (Stream<String> lines = bodyLines) {
                        if (response.statusCode() >= 400) {
                            throw new BizException(
                                    500,
                                    "LLM stream request failed, status="
                                            + response.statusCode());
                        }
                        Iterator<String> iterator = lines.iterator();
                        while (!sink.isCancelled() && iterator.hasNext()) {
                            if (handleStreamLine(iterator.next(), sink)) {
                                break;
                            }
                        }
                    } finally {
                        responseLines.compareAndSet(bodyLines, null);
                    }
                    if (cancelled.get() || sink.isCancelled()) {
                        span.result("cancelled");
                    } else {
                        span.result("success");
                        sink.complete();
                    }
                } catch (InterruptedException e) {
                    if (cancelled.get() || sink.isCancelled()) {
                        Thread.interrupted();
                        span.result("cancelled");
                    } else {
                        Thread.currentThread().interrupt();
                        span.error(e);
                        sink.error(e);
                    }
                } catch (Exception e) {
                    if (cancelled.get() || sink.isCancelled()) {
                        span.result("cancelled");
                    } else {
                        span.error(e);
                        sink.error(e);
                    }
                } catch (Error error) {
                    span.error(error);
                    throw error;
                } finally {
                    exchange.set(null);
                    closeResponseLines(responseLines);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ObjectNode buildRequest(String prompt, boolean stream, AgentModelSettings settings) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", settings.getLlmModel());
        root.put("temperature", temperature);
        root.put("stream", stream);

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt == null ? "" : prompt);

        if ("deepseek".equalsIgnoreCase(settings.getLlmProvider())) {
            ObjectNode thinkingNode = root.putObject("thinking");
            thinkingNode.put("type", thinking ? "enabled" : "disabled");
            if (!reasoningEffort.isBlank()) {
                root.put("reasoning_effort", reasoningEffort);
            }
        }
        return root;
    }

    private HttpRequest.Builder baseRequest(URI endpoint, String apiKey) {
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);
    }

    private URI chatEndpoint(AgentModelSettings settings) {
        return URI.create(trimTrailingSlash(settings.getLlmBaseUrl(), "https://api.deepseek.com") + "/chat/completions");
    }

    private String extractMessageContent(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new BizException(500, "LLM response does not contain message content");
        }
        return content.asText();
    }

    private boolean handleStreamLine(
            String line,
            reactor.core.publisher.FluxSink<String> sink) {
        if (line == null || line.isBlank() || !line.startsWith("data:")) {
            return false;
        }
        String data = line.substring("data:".length()).trim();
        if ("[DONE]".equals(data)) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode delta = root.path("choices").path(0).path("delta").path("content");
            if (!delta.isMissingNode() && !delta.isNull()) {
                String text = delta.asText();
                if (!text.isEmpty()) {
                    sink.next(text);
                }
            }
        } catch (IOException e) {
            throw new BizException(500, "Failed to parse LLM stream response");
        }
        return false;
    }

    private void ensureApiKey(AgentModelSettings settings) {
        if (settings.getLlmApiKey() == null || settings.getLlmApiKey().isBlank()) {
            throw new BizException(500, "LLM API key is empty. Set it in the frontend settings page.");
        }
    }

    private void ensureSuccess(int statusCode) {
        if (statusCode >= 400) {
            throw new BizException(
                    500, "LLM request failed, status=" + statusCode);
        }
    }

    private HttpResponse<Stream<String>> awaitStreamingResponse(
            CompletableFuture<HttpResponse<Stream<String>>> pending)
            throws Exception {
        try {
            return pending.get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "LLM stream request failed without a throwable cause");
        }
    }

    private void abortStream(
            AtomicBoolean cancelled,
            AtomicReference<CompletableFuture<HttpResponse<Stream<String>>>>
                    exchange,
            AtomicReference<Stream<String>> responseLines) {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<?> pending = exchange.getAndSet(null);
        if (pending != null) {
            pending.cancel(true);
        }
        closeResponseLines(responseLines);
    }

    private void closeResponseLines(
            AtomicReference<Stream<String>> responseLines) {
        Stream<String> lines = responseLines.getAndSet(null);
        if (lines == null) {
            return;
        }
        try {
            lines.close();
        } catch (RuntimeException ignored) {
            // 取消/终止期间尽力释放 provider 响应体，不改变业务终态。
        }
    }

    private boolean isLocal(String provider) {
        return "local".equalsIgnoreCase(provider);
    }

    private String localCall(String prompt) {
        if (prompt != null && prompt.contains("SHORT_ANSWER_GRADING_JSON")) {
            return """
                    {
                      "correct": true,
                      "feedback": "本地模型占位判题：已接入 RAG 判题流程。配置真实 LLM 后会根据课程材料、标准答案和学生答案进行语义判定。"
                    }
                    """;
        }
        if (prompt != null && prompt.contains("JSON") && prompt.contains("type")) {
            return """
                    [
                      {
                        "type": "single_choice",
                        "stem": "According to the retrieved course material, which option is correct?",
                        "options": ["A. The answer should be based on the course material", "B. The answer should ignore the material", "C. The answer should be random", "D. The answer should be unrelated"],
                        "answer": "A",
                        "explanation": "The RAG workflow requires generated questions to be grounded in retrieved course chunks.",
                        "difficulty": "medium",
                        "knowledgePoint": "RAG workflow",
                        "sourceChunkId": null
                      }
                    ]
                    """;
        }
        return "Local RAG answer: the system retrieved course chunks and built a prompt successfully. "
                + "Configure a real model in settings if you need natural-language model quality.\n\n"
                + preview(prompt);
    }

    private String preview(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        return prompt.substring(0, Math.min(prompt.length(), 600));
    }

    private String trimTrailingSlash(String value, String fallback) {
        String text = value == null || value.isBlank() ? fallback : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
