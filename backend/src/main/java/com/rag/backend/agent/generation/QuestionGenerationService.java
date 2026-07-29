package com.rag.backend.agent.generation;

import com.rag.backend.question.model.Question;

import java.util.List;

// 区分“只生成模型文本”和“解析后持久化题目”两种入口，调用方据此选择是否产生数据库副作用。
public interface QuestionGenerationService {
    String generateQuestions(Long courseId, String requirement);

    List<Question> generateAndSave(Long courseId, String requirement);
}
