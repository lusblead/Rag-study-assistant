package com.rag.backend.agent.generation;

import com.rag.backend.question.model.Question;

import java.util.List;

public interface QuestionGenerationService {
    String generateQuestions(Long courseId, String requirement);

    List<Question> generateAndSave(Long courseId, String requirement);
}