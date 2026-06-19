package com.rag.backend.agent.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.GeneratedQuestionItem;
import com.rag.backend.agent.prompt.QuestionPromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.common.BizException;
import com.rag.backend.question.QuestionService;
import com.rag.backend.question.model.Question;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class DefaultQuestionGenerationService implements QuestionGenerationService {
    private static final Set<String> ALLOWED_TYPES = Set.of(
            Question.TYPE_SINGLE_CHOICE,
            Question.TYPE_MULTI_CHOICE,
            Question.TYPE_TRUE_FALSE,
            Question.TYPE_SHORT_ANSWER
    );
    private static final Set<String> ALLOWED_DIFFICULTIES = Set.of(
            Question.DIFF_EASY,
            Question.DIFF_MEDIUM,
            Question.DIFF_HARD
    );

    private final KnowledgeRetriever knowledgeRetriever;
    private final QuestionService questionService;
    private final QuestionPromptTemplate promptTemplate;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int topK;

    public DefaultQuestionGenerationService(KnowledgeRetriever knowledgeRetriever,
                                            QuestionService questionService,
                                            QuestionPromptTemplate promptTemplate,
                                            ChatClient chatClient,
                                            ObjectMapper objectMapper,
                                            @Value("${rag.top-k:5}") int topK) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.questionService = questionService;
        this.promptTemplate = promptTemplate;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.topK = topK;
    }

    @Override
    public String generateQuestions(Long courseId, String requirement) {
        List<Question> saved = generateAndSave(courseId, requirement);
        return "Generated and saved " + saved.size() + " question(s).";
    }

    @Override
    public List<Question> generateAndSave(Long courseId, String requirement) {
        List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(courseId, requirement, topK);
        String prompt = promptTemplate.render(requirement, chunks);
        String response = chatClient.call(prompt);

        List<Question> questions = parseQuestions(courseId, response, chunks);
        if (questions.isEmpty()) {
            questions.add(fallbackQuestion(courseId, chunks));
        }
        return questionService.batchSave(questions);
    }

    private List<Question> parseQuestions(Long courseId, String response, List<RetrievedChunk> chunks) {
        String json = extractJsonArray(response);
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            List<GeneratedQuestionItem> items = objectMapper.readValue(
                    json,
                    new TypeReference<List<GeneratedQuestionItem>>() {}
            );
            List<Question> questions = new ArrayList<>();
            for (GeneratedQuestionItem item : items) {
                Question question = toQuestion(courseId, item, chunks);
                if (question != null) {
                    questions.add(question);
                }
            }
            return questions;
        } catch (JsonProcessingException e) {
            throw new BizException(500, "AI question output is not valid JSON. Please retry.");
        }
    }

    private Question toQuestion(Long courseId, GeneratedQuestionItem item, List<RetrievedChunk> chunks) throws JsonProcessingException {
        if (item == null || !StringUtils.hasText(item.getStem()) || !StringUtils.hasText(item.getAnswer())) {
            return null;
        }

        String type = normalizeType(item.getType());
        String difficulty = normalizeDifficulty(item.getDifficulty());
        if (type == null || difficulty == null) {
            return null;
        }

        Question question = new Question();
        question.setCourseId(courseId);
        question.setSourceChunkId(resolveSourceChunkId(item.getSourceChunkId(), chunks));
        question.setType(type);
        question.setStem(item.getStem().trim());
        question.setOptions(item.getOptions() == null ? null : objectMapper.writeValueAsString(item.getOptions()));
        question.setAnswer(item.getAnswer().trim());
        question.setExplanation(StringUtils.hasText(item.getExplanation()) ? item.getExplanation().trim() : "Generated from course material.");
        question.setDifficulty(difficulty);
        question.setKnowledgePoint(StringUtils.hasText(item.getKnowledgePoint()) ? item.getKnowledgePoint().trim() : "Course material");
        return question;
    }

    private String normalizeType(String value) {
        if (!StringUtils.hasText(value)) {
            return Question.TYPE_SINGLE_CHOICE;
        }
        String type = value.trim();
        return ALLOWED_TYPES.contains(type) ? type : null;
    }

    private String normalizeDifficulty(String value) {
        if (!StringUtils.hasText(value)) {
            return Question.DIFF_MEDIUM;
        }
        String difficulty = value.trim();
        return ALLOWED_DIFFICULTIES.contains(difficulty) ? difficulty : null;
    }

    private Long resolveSourceChunkId(Long sourceChunkId, List<RetrievedChunk> chunks) {
        if (sourceChunkId != null) {
            return sourceChunkId;
        }
        return chunks.isEmpty() ? null : chunks.get(0).chunkId();
    }

    private String extractJsonArray(String response) {
        if (!StringUtils.hasText(response)) {
            return null;
        }
        String trimmed = response.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private Question fallbackQuestion(Long courseId, List<RetrievedChunk> chunks) {
        RetrievedChunk source = chunks.isEmpty() ? null : chunks.get(0);
        Question question = new Question();
        question.setCourseId(courseId);
        question.setSourceChunkId(source == null ? null : source.chunkId());
        question.setType(Question.TYPE_SINGLE_CHOICE);
        question.setStem("According to the retrieved course material, which statement is correct?");
        question.setOptions("[\"A. The answer should be grounded in course material\",\"B. The answer should ignore course material\",\"C. The answer should be random\",\"D. The answer should be unrelated\"]");
        question.setAnswer("A");
        question.setExplanation(source == null ? "No course material was retrieved." : "Based on chunk: " + source.content());
        question.setDifficulty(Question.DIFF_MEDIUM);
        question.setKnowledgePoint("Course material");
        return question;
    }
}