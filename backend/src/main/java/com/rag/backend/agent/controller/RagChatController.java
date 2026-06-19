package com.rag.backend.agent.controller;

import com.rag.backend.agent.chat.RagChatService;
import com.rag.backend.agent.model.RagChatRequest;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.common.BizException;
import com.rag.backend.common.Result;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/chat")
public class RagChatController {
    private final RagChatService ragChatService;

    public RagChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping
    public Result<RagChatResponse> chat(@RequestBody RagChatRequest request) {
        if (request.getCourseId() == null) {
            throw new BizException(400, "courseId 不能为空");
        }
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new BizException(400, "question 不能为空");
        }
        return Result.ok(ragChatService.chat(request.getCourseId(), request.getQuestion()));
    }
}