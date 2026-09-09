package com.rag.backend.agent.controller;

import com.rag.backend.agent.chat.RagChatService;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.history.ChatSession;
import com.rag.backend.agent.model.RagChatRequest;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagChatStreamResponse;
import com.rag.backend.common.BizException;
import com.rag.backend.common.Result;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/agent/chat")
// 提供普通问答、流式问答和会话历史接口。
public class RagChatController {
    private static final Map<String, String> STREAM_ERROR_PAYLOAD = Map.of(
            "code", "CHAT_STREAM_FAILED",
            "message", "聊天处理失败，请稍后重试");

    private final RagChatService ragChatService;
    private final ChatHistoryService chatHistoryService;
    private final TraceContextService traces;

    @Autowired
    public RagChatController(RagChatService ragChatService,
                             ChatHistoryService chatHistoryService,
                             TraceContextService traces) {
        this.ragChatService = ragChatService;
        this.chatHistoryService = chatHistoryService;
        this.traces = traces;
    }

    /** 保留现有测试与手工装配入口。 */
    public RagChatController(RagChatService ragChatService,
                             ChatHistoryService chatHistoryService) {
        this(ragChatService, chatHistoryService, new TraceContextService());
    }

    @PostMapping
    public Result<RagChatResponse> chat(@RequestBody RagChatRequest request) {
        validate(request);
        return Result.ok(ragChatService.chat(request.getCourseId(), request.getSessionId(), request.getQuestion()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody RagChatRequest request) {
        validate(request);

        SseEmitter emitter = createEmitter();
        TraceCarrier asyncCarrier = traces.capture(null);
        AtomicBoolean terminalRecorded = new AtomicBoolean();
        RagChatStreamResponse response;
        try {
            response = ragChatService.stream(request.getCourseId(), request.getSessionId(), request.getQuestion());
        } catch (Exception e) {
            boolean delivered = send(emitter, "error", errorPayload(e));
            recordTerminal(
                    asyncCarrier,
                    delivered ? "error" : "send_failed",
                    e,
                    terminalRecorded);
            emitter.complete();
            return emitter;
        }
        AtomicReference<TraceCarrier> signalCarrier = new AtomicReference<>(
                asyncCarrier);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        AtomicBoolean serverTerminal = new AtomicBoolean();

        emitter.onCompletion(() -> runSignal(
                signalCarrier.get(), "chat.sse.emitter", span -> {
                    if (!serverTerminal.get()) {
                        span.result("cancelled");
                        cancelSubscription(
                                signalCarrier.get(), "cancelled", null,
                                subscription, cancellationRequested,
                                terminalRecorded);
                    } else {
                        span.result("completed");
                    }
                }));
        emitter.onTimeout(() -> runSignal(
                signalCarrier.get(), "chat.sse.emitter", span -> {
                    span.result("timeout");
                    cancelSubscription(
                            signalCarrier.get(), "timeout", null,
                            subscription, cancellationRequested,
                            terminalRecorded);
                }));
        emitter.onError(error -> runSignal(
                signalCarrier.get(), "chat.sse.emitter", span -> {
                    span.error(error).result("send_failed");
                    cancelSubscription(
                            signalCarrier.get(), "send_failed", error,
                            subscription, cancellationRequested,
                            terminalRecorded);
                }));

        if (!send(emitter, "session", Map.of("sessionId", response.sessionId()))
                || !send(emitter, "references", response.references())
                || !send(emitter, "metadata", response.metadata())) {
            serverTerminal.set(true);
            cancelSubscription(
                    asyncCarrier, "send_failed", null,
                    subscription, cancellationRequested, terminalRecorded);
            emitter.complete();
            return emitter;
        }

        Runnable subscribeTask = traces.wrap(
                asyncCarrier, "chat.sse.subscribe", () -> {
                    TraceCarrier callbacks = traces.capture(null);
                    signalCarrier.set(callbacks);
                    AtomicReference<Throwable> streamFailure =
                            new AtomicReference<>();
                    Flux<String> tracedStream = response.stream()
                            .doFinally(signal -> recordTerminal(
                                    callbacks,
                                    terminalOutcome(signal),
                                    streamFailure.get(),
                                    terminalRecorded));
                    BaseSubscriber<String> subscriber =
                            new BaseSubscriber<>() {
                        @Override
                        protected void hookOnSubscribe(
                                Subscription upstream) {
                            subscription.set(this);
                            if (cancellationRequested.get()) {
                                cancel();
                            } else {
                                requestUnbounded();
                            }
                        }

                        @Override
                        protected void hookOnNext(String chunk) {
                            runSignal(
                                    callbacks, "chat.sse.delta", span -> {
                                        if (cancellationRequested.get()) {
                                            span.result("ignored");
                                            return;
                                        }
                                        if (send(emitter, "delta", chunk)) {
                                            span.result("sent");
                                        } else {
                                            span.result("send_failed");
                                            serverTerminal.set(true);
                                            cancelSubscription(
                                                    callbacks, "send_failed", null,
                                                    subscription,
                                                    cancellationRequested,
                                                    terminalRecorded);
                                            emitter.complete();
                                        }
                                    });
                        }

                        @Override
                        protected void hookOnError(Throwable error) {
                            streamFailure.set(error);
                            runSignal(callbacks, "chat.sse.error", span -> {
                                if (cancellationRequested.get()) {
                                    span.result("ignored");
                                    return;
                                }
                                span.error(error);
                                serverTerminal.set(true);
                                if (send(emitter, "error",
                                        errorPayload(error))) {
                                    span.result("sent");
                                } else {
                                    span.result("send_failed");
                                    recordTerminal(
                                            callbacks,
                                            "send_failed",
                                            error,
                                            terminalRecorded);
                                }
                                emitter.complete();
                            });
                        }

                        @Override
                        protected void hookOnComplete() {
                            runSignal(
                                    callbacks, "chat.sse.complete", span -> {
                                        if (cancellationRequested.get()) {
                                            span.result("ignored");
                                            return;
                                        }
                                        serverTerminal.set(true);
                                        if (send(emitter, "done", "[DONE]")) {
                                            span.result("sent");
                                        } else {
                                            span.result("send_failed");
                                            recordTerminal(
                                                    callbacks, "send_failed",
                                                    null, terminalRecorded);
                                        }
                                        emitter.complete();
                                    });
                        }
                    };
                    if (cancellationRequested.get()) {
                        subscriber.dispose();
                    }
                    tracedStream.subscribe(subscriber);
                });
        CompletableFuture.runAsync(subscribeTask)
                .exceptionally(error -> {
                    boolean delivered = send(
                            emitter, "error", STREAM_ERROR_PAYLOAD);
                    recordTerminal(
                            signalCarrier.get(),
                            delivered ? "error" : "send_failed",
                            error,
                            terminalRecorded);
                    serverTerminal.set(true);
                    emitter.complete();
                    return null;
                });
        return emitter;
    }

    @GetMapping("/sessions")
    public Result<List<ChatSession>> listSessions(@RequestParam Long courseId) {
        return Result.ok(chatHistoryService.listSessions(courseId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessage>> listMessages(@PathVariable Long sessionId) {
        return Result.ok(chatHistoryService.listMessages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        chatHistoryService.deleteSession(sessionId);
        return Result.ok();
    }

    private void validate(RagChatRequest request) {
        if (request.getCourseId() == null) {
            throw new BizException(400, "courseId 不能为空");
        }
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new BizException(400, "question 不能为空");
        }
    }

    protected boolean send(
            SseEmitter emitter, String eventName, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> errorPayload(Throwable error) {
        if (error instanceof com.rag.backend.agent.materials.MaterialScopeException material) {
            return Map.of("code", "MATERIAL_SCOPE_UNAVAILABLE", "reason", material.reason(), "message", material.getMessage());
        }
        return STREAM_ERROR_PAYLOAD;
    }

    /** 测试可替换发送边界；生产仍使用无限应用层超时的标准 emitter。 */
    protected SseEmitter createEmitter() {
        return new SseEmitter(0L);
    }

    private void runSignal(
            TraceCarrier carrier,
            String operation,
            Consumer<TraceSpan> action) {
        try (TraceSpan span = traces.continueOrStart(
                carrier, operation, carrier.correlationId())) {
            try {
                action.accept(span);
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private void cancelSubscription(
            TraceCarrier carrier,
            String outcome,
            Throwable error,
            AtomicReference<Disposable> subscription,
            AtomicBoolean cancellationRequested,
            AtomicBoolean terminalRecorded) {
        cancellationRequested.set(true);
        recordTerminal(carrier, outcome, error, terminalRecorded);
        Disposable current = subscription.get();
        if (current != null) {
            current.dispose();
        }
    }

    private void recordTerminal(
            TraceCarrier carrier,
            String outcome,
            Throwable error,
            AtomicBoolean terminalRecorded) {
        if (!terminalRecorded.compareAndSet(false, true)) {
            return;
        }
        runSignal(carrier, "chat.sse.terminal", span -> {
            if (error != null) {
                span.error(error);
            }
            span.result(outcome);
        });
    }

    private String terminalOutcome(SignalType signal) {
        return switch (signal) {
            case ON_COMPLETE -> "completed";
            case ON_ERROR -> "error";
            case CANCEL -> "cancelled";
            default -> signal.name().toLowerCase();
        };
    }
}
