package com.rag.backend.agent.controller;

import com.rag.backend.agent.generation.QuestionGenerationService;
import com.rag.backend.agent.model.QuestionGenerateRequest;
import com.rag.backend.common.BizException;
import com.rag.backend.common.Result;
import com.rag.backend.question.model.Question;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/questions")
// 提供 AI 出题并保存题库的接口。
public class QuestionGenerationController {
    private final QuestionGenerationService generationService;

    public QuestionGenerationController(QuestionGenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping("/generate")
    public Result<List<Question>> generate(@RequestBody QuestionGenerateRequest request) {
        if (request.getCourseId() == null) {
            throw new BizException(400, "courseId 不能为空");
        }
        if (!StringUtils.hasText(request.getRequirement())) {
            throw new BizException(400, "requirement 不能为空");
        }
        return Result.ok(generationService.generateAndSave(request.getCourseId(), request.getRequirement()));
    }
}