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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agent/chat")
public class RagChatController {
    private final RagChatService ragChatService;
    private final ChatHistoryService chatHistoryService;

    public RagChatController(RagChatService ragChatService,
                             ChatHistoryService chatHistoryService) {
        this.ragChatService = ragChatService;
        this.chatHistoryService = chatHistoryService;
    }

    @PostMapping
    public Result<RagChatResponse> chat(@RequestBody RagChatRequest request) {
        validate(request);
        return Result.ok(ragChatService.chat(request.getCourseId(), request.getSessionId(), request.getQuestion()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody RagChatRequest request) {
        validate(request);

        SseEmitter emitter = new SseEmitter(0L);
        RagChatStreamResponse response = ragChatService.stream(request.getCourseId(), request.getSessionId(), request.getQuestion());
        send(emitter, "session", Map.of("sessionId", response.sessionId()));
        send(emitter, "references", response.references());
        CompletableFuture.runAsync(() -> response.stream()
                .subscribe(
                        chunk -> send(emitter, "delta", chunk),
                        error -> {
                            send(emitter, "error", Map.of("message", String.valueOf(error.getMessage())));
                            emitter.completeWithError(error);
                        },
                        () -> {
                            send(emitter, "done", "[DONE]");
                            emitter.complete();
                        }
                ));
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

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
