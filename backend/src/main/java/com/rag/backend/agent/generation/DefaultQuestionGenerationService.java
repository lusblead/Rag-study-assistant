package com.rag.backend.agent.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.llm.ChatClient;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class DefaultQuestionGenerationService implements QuestionGenerationService {
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
        if (courseId == null) {
            throw new BizException(400, "courseId cannot be empty");
        }
        if (!StringUtils.hasText(requirement)) {
            throw new BizException(400, "requirement cannot be empty");
        }

        List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(courseId, requirement, topK);
        if (chunks.isEmpty()) {
            throw new BizException(400, "No parsed knowledge chunks were retrieved for this course. Upload and ingest course material first.");
        }

        String prompt = promptTemplate.render(requirement, chunks);
        String response = chatClient.call(prompt);
        List<Question> questions = parseQuestions(courseId, response, chunks);
        if (questions.isEmpty()) {
            questions.add(fallbackQuestion(courseId, chunks));
        }
        return questionService.batchSave(questions);
    }

    private List<Question> parseQuestions(Long courseId, String response, List<RetrievedChunk> chunks) {
        JsonNode array = extractQuestionArray(response);
        List<Question> questions = new ArrayList<>();
        for (JsonNode item : array) {
            Question question = toQuestion(courseId, item, chunks);
            if (question != null) {
                questions.add(question);
            }
        }
        return questions;
    }

    private Question toQuestion(Long courseId, JsonNode item, List<RetrievedChunk> chunks) {
        if (item == null || !item.isObject()) {
            return null;
        }

        String stem = text(item, "stem", "question", "title");
        String answer = text(item, "answer", "correctAnswer", "standardAnswer");
        if (!StringUtils.hasText(stem) || !StringUtils.hasText(answer)) {
            return null;
        }

        String type = normalizeType(text(item, "type", "questionType"));
        String difficulty = normalizeDifficulty(text(item, "difficulty", "level"));
        List<String> options = normalizeOptions(item.get("options"), type);
        String normalizedAnswer = normalizeAnswer(answer, type, options);

        if (isChoiceType(type) && options.size() < 2) {
            return null;
        }
        if (!StringUtils.hasText(normalizedAnswer)) {
            return null;
        }

        Question question = new Question();
        question.setCourseId(courseId);
        question.setSourceChunkId(resolveSourceChunkId(longValue(item, "sourceChunkId", "chunkId"), chunks));
        question.setType(type);
        question.setStem(stem.trim());
        question.setOptions(options.isEmpty() ? null : writeJson(options));
        question.setAnswer(normalizedAnswer);
        question.setExplanation(defaultText(text(item, "explanation", "analysis"), "Generated from course material."));
        question.setDifficulty(difficulty);
        question.setKnowledgePoint(defaultText(text(item, "knowledgePoint", "knowledge_point", "topic"), "Course material"));
        return question;
    }

    private JsonNode extractQuestionArray(String response) {
        if (!StringUtils.hasText(response)) {
            throw new BizException(500, "AI question output is empty.");
        }

        List<String> candidates = jsonCandidates(response);
        JsonProcessingException lastError = null;
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(candidate);
                JsonNode array = resolveArrayNode(node);
                if (array != null && array.isArray()) {
                    return array;
                }
            } catch (JsonProcessingException e) {
                lastError = e;
            }
        }

        String reason = lastError == null ? "No JSON array found." : lastError.getOriginalMessage();
        throw new BizException(500, "AI question output is not valid JSON: " + reason
                + ". Output preview: " + abbreviate(response, 500));
    }

    private List<String> jsonCandidates(String response) {
        String trimmed = response.trim();
        List<String> candidates = new ArrayList<>();
        candidates.add(trimmed);

        int firstFence = trimmed.indexOf("```");
        int lastFence = trimmed.lastIndexOf("```");
        if (firstFence >= 0 && lastFence > firstFence) {
            String fenced = trimmed.substring(firstFence + 3, lastFence).trim();
            if (fenced.regionMatches(true, 0, "json", 0, 4)) {
                fenced = fenced.substring(4).trim();
            }
            candidates.add(fenced);
        }

        int arrayStart = trimmed.indexOf('[');
        int arrayEnd = trimmed.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            candidates.add(trimmed.substring(arrayStart, arrayEnd + 1));
        }

        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            candidates.add(trimmed.substring(objectStart, objectEnd + 1));
        }

        return candidates;
    }

    private JsonNode resolveArrayNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        JsonNode questions = node.path("questions");
        if (questions.isArray()) {
            return questions;
        }
        JsonNode data = node.path("data");
        if (data.isArray()) {
            return data;
        }
        JsonNode items = node.path("items");
        return items.isArray() ? items : null;
    }

    private String normalizeType(String value) {
        if (!StringUtils.hasText(value)) {
            return Question.TYPE_SINGLE_CHOICE;
        }
        String text = value.trim().toLowerCase()
                .replace('-', '_')
                .replace(' ', '_');
        return switch (text) {
            case "single_choice", "single", "choice", "radio", "单选", "单选题", "选择题" -> Question.TYPE_SINGLE_CHOICE;
            case "multi_choice", "multiple_choice", "multiple", "multi", "多选", "多选题" -> Question.TYPE_MULTI_CHOICE;
            case "true_false", "truefalse", "true/false", "判断", "判断题" -> Question.TYPE_TRUE_FALSE;
            case "short_answer", "short", "qa", "简答", "简答题", "问答题" -> Question.TYPE_SHORT_ANSWER;
            default -> Question.TYPE_SINGLE_CHOICE;
        };
    }

    private String normalizeDifficulty(String value) {
        if (!StringUtils.hasText(value)) {
            return Question.DIFF_MEDIUM;
        }
        String text = value.trim().toLowerCase();
        return switch (text) {
            case "easy", "simple", "low", "简单", "容易", "基础" -> Question.DIFF_EASY;
            case "hard", "difficult", "high", "困难", "较难", "难" -> Question.DIFF_HARD;
            default -> Question.DIFF_MEDIUM;
        };
    }

    private List<String> normalizeOptions(JsonNode optionsNode, String type) {
        if (Question.TYPE_SHORT_ANSWER.equals(type)) {
            return List.of();
        }
        if (Question.TYPE_TRUE_FALSE.equals(type) && (optionsNode == null || optionsNode.isNull())) {
            return List.of("正确", "错误");
        }

        List<String> options = new ArrayList<>();
        if (optionsNode == null || optionsNode.isNull()) {
            return options;
        }
        if (optionsNode.isArray()) {
            for (JsonNode option : optionsNode) {
                String value = option.asText("");
                if (StringUtils.hasText(value)) {
                    options.add(value.trim());
                }
            }
            return options;
        }
        if (optionsNode.isTextual()) {
            String raw = optionsNode.asText();
            try {
                JsonNode parsed = objectMapper.readTree(raw);
                if (parsed.isArray()) {
                    return normalizeOptions(parsed, type);
                }
            } catch (JsonProcessingException ignored) {
                // Fall back to delimiter splitting below.
            }
            for (String value : raw.split("\\n|;|；")) {
                if (StringUtils.hasText(value)) {
                    options.add(value.trim());
                }
            }
        }
        return options;
    }

    private String normalizeAnswer(String answer, String type, List<String> options) {
        String text = answer == null ? "" : answer.trim();
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (isChoiceType(type)) {
            String letters = choiceLetters(text);
            if (StringUtils.hasText(letters)) {
                return Question.TYPE_SINGLE_CHOICE.equals(type) ? letters.substring(0, 1) : letters;
            }
            for (String option : options) {
                String optionText = option.trim();
                if (optionText.equalsIgnoreCase(text) || optionText.toLowerCase().contains(text.toLowerCase())) {
                    String optionLetter = choiceLetters(optionText);
                    if (StringUtils.hasText(optionLetter)) {
                        return optionLetter.substring(0, 1);
                    }
                }
            }
        }
        if (Question.TYPE_TRUE_FALSE.equals(type)) {
            String lower = text.toLowerCase();
            if (Set.of("true", "t", "yes", "y", "正确", "对", "是").contains(lower)) {
                return "正确";
            }
            if (Set.of("false", "f", "no", "n", "错误", "错", "否").contains(lower)) {
                return "错误";
            }
        }
        return text;
    }

    private String choiceLetters(String value) {
        Set<Character> letters = new LinkedHashSet<>();
        for (char ch : value.toUpperCase().toCharArray()) {
            if (ch >= 'A' && ch <= 'D') {
                letters.add(ch);
            }
        }
        return letters.stream()
                .sorted(Comparator.naturalOrder())
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
    }

    private boolean isChoiceType(String type) {
        return Question.TYPE_SINGLE_CHOICE.equals(type) || Question.TYPE_MULTI_CHOICE.equals(type);
    }

    private Long resolveSourceChunkId(Long sourceChunkId, List<RetrievedChunk> chunks) {
        if (sourceChunkId != null) {
            for (RetrievedChunk chunk : chunks) {
                if (sourceChunkId.equals(chunk.chunkId())) {
                    return sourceChunkId;
                }
            }
        }
        return chunks.isEmpty() ? null : chunks.get(0).chunkId();
    }

    private String text(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                String text = value.asText("");
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private Long longValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.canConvertToLong()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String writeJson(List<String> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new BizException(500, "Failed to serialize question options.");
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private Question fallbackQuestion(Long courseId, List<RetrievedChunk> chunks) {
        RetrievedChunk source = chunks.isEmpty() ? null : chunks.get(0);
        Question question = new Question();
        question.setCourseId(courseId);
        question.setSourceChunkId(source == null ? null : source.chunkId());
        question.setType(Question.TYPE_SINGLE_CHOICE);
        question.setStem("根据课程资料，下列哪一项说法是正确的？");
        question.setOptions("[\"A. 答案应基于课程资料\",\"B. 答案应忽略课程资料\",\"C. 答案应随机猜测\",\"D. 答案应与课程无关\"]");
        question.setAnswer("A");
        question.setExplanation(source == null ? "未检索到课程资料。" : "依据检索到的课程片段生成。");
        question.setDifficulty(Question.DIFF_MEDIUM);
        question.setKnowledgePoint("课程资料");
        return question;
    }
}
