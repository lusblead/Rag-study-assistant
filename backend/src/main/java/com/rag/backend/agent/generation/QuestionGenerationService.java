package com.rag.backend.agent.generation;

import com.rag.backend.question.model.Question;

import java.util.List;

// 定义 AI 出题服务的统一接口。
public interface QuestionGenerationService {
    String generateQuestions(Long courseId, String requirement);

    List<Question> generateAndSave(Long courseId, String requirement);
}