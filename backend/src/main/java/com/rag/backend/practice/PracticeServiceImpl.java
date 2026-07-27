package com.rag.backend.practice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.course.CourseMapper;
import com.rag.backend.practice.model.PracticeRecord;
import com.rag.backend.practice.model.PracticeSubResult;
import com.rag.backend.question.QuestionMapper;
import com.rag.backend.question.model.Question;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.HashMap;
import java.math.BigDecimal;

@Service
public class PracticeServiceImpl implements PracticeService {

    private final PracticeMapper practiceMapper;
    private final CourseMapper courseMapper;
    private final QuestionMapper questionMapper;
    private final KnowledgeRetriever knowledgeRetriever;
    private final KnowledgeChunkRepository chunkRepository;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int gradingTopK;

    public PracticeServiceImpl(PracticeMapper practiceMapper,
                               CourseMapper courseMapper,
                               QuestionMapper questionMapper,
                               KnowledgeRetriever knowledgeRetriever,
                               KnowledgeChunkRepository chunkRepository,
                               ChatClient chatClient,
                               ObjectMapper objectMapper,
                               @Value("${practice.ai-grading.top-k:5}") int gradingTopK) {
        this.practiceMapper = practiceMapper;
        this.courseMapper = courseMapper;
        this.questionMapper = questionMapper;
        this.knowledgeRetriever = knowledgeRetriever;
        this.chunkRepository = chunkRepository;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.gradingTopK = gradingTopK;
    }

    @Override
    public PracticeRecord submit(Long courseId, Long questionId, String userAnswer) {
        return submit(courseId, questionId, userAnswer, null);
    }

    @Override
    public PracticeRecord submit(Long courseId, Long questionId, String userAnswer, String answerPayload) {
        if (courseMapper.selectById(courseId) == null) {
            throw new IllegalArgumentException("Course does not exist: " + courseId);
        }

        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("Question does not exist: " + questionId);
        }

        GradingResult grading = answerPayload == null
                ? gradeAnswer(courseId, question, userAnswer)
                : gradeCompositeAnswer(question, answerPayload);

        PracticeRecord record = new PracticeRecord();
        record.setCourseId(courseId);
        record.setQuestionId(questionId);
        record.setUserAnswer(userAnswer);
        record.setIsCorrect(grading.correct());
        record.setGradingMode(grading.mode());
        record.setGradingFeedback(grading.feedback());
        record.setAnswerPayload(answerPayload);
        record.setScore(grading.score());
        record.setMaxScore(grading.maxScore());
        record.setGradingStatus(grading.status());
        record.setSubResults(grading.subResults());

        practiceMapper.insert(record);
        return record;
    }

    @Override
    public List<PracticeRecord> listRecords(Long courseId) {
        return practiceMapper.selectListByCourseId(courseId);
    }

    @Override
    public List<PracticeRecord> listWrongQuestions(Long courseId) {
        return practiceMapper.selectWrongByCourseId(courseId);
    }

    @Override
    public PracticeRecord manualGrade(Long recordId, BigDecimal score, BigDecimal maxScore, String feedback) {
        PracticeRecord record = practiceMapper.selectById(recordId);
        if (record == null) throw new IllegalArgumentException("练习记录不存在: " + recordId);
        if (!"manual_required".equals(record.getGradingStatus()) && !"graded".equals(record.getGradingStatus()))
            throw new IllegalArgumentException("当前记录不可人工批改");
        BigDecimal resolvedMax = maxScore != null ? maxScore : record.getMaxScore();
        if (score == null || resolvedMax == null || resolvedMax.signum() <= 0 || score.signum() < 0 || score.compareTo(resolvedMax) > 0)
            throw new IllegalArgumentException("score 必须在 0 到 maxScore 之间");
        record.setScore(score); record.setMaxScore(resolvedMax); record.setGradingFeedback(feedback);
        record.setGradingMode("manual"); record.setGradingStatus("graded");
        record.setIsCorrect(score.compareTo(resolvedMax) == 0);
        practiceMapper.updateManualGrade(record);
        return practiceMapper.selectById(recordId);
    }

    private String normalizeAnswer(String type, String answer) {
        if (answer == null) {
            return "";
        }
        String text = answer.trim();
        if (text.isBlank()) {
            return "";
        }

        if (Question.TYPE_SINGLE_CHOICE.equals(type)) {
            String letters = choiceLetters(text);
            return letters.isBlank() ? text.toUpperCase() : letters.substring(0, 1);
        }
        if (Question.TYPE_MULTI_CHOICE.equals(type)) {
            String letters = choiceLetters(text);
            return letters.isBlank() ? text.toUpperCase().replaceAll("\\s+", "") : letters;
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
        return text.replaceAll("\\s+", " ").trim();
    }

    private GradingResult gradeAnswer(Long courseId, Question question, String userAnswer) {
        if (Question.TYPE_SHORT_ANSWER.equals(question.getType())) {
            return gradeShortAnswerWithAi(courseId, question, userAnswer);
        }

        if (Set.of(Question.TYPE_COMPOSITION, Question.TYPE_TRANSLATION,
                Question.TYPE_EXPLANATION, Question.TYPE_SENTENCE_BREAK).contains(question.getType())
                || Question.GRADING_MANUAL.equals(question.getGradingStrategy())) {
            return new GradingResult(null, Question.GRADING_MANUAL,
                    "答案已保存，等待人工评价。", null, null, "manual_required", List.of());
        }

        String standardAnswer = normalizeAnswer(question.getType(), question.getAnswer());
        String submittedAnswer = normalizeAnswer(question.getType(), userAnswer);
        boolean isCorrect = !standardAnswer.isBlank() && standardAnswer.equalsIgnoreCase(submittedAnswer);
        String feedback = isCorrect ? "Rule grading: answer matches the standard answer."
                : "Rule grading: answer does not match the standard answer.";
        BigDecimal score = isCorrect ? BigDecimal.ONE : BigDecimal.ZERO;
        return new GradingResult(isCorrect, Question.GRADING_RULE, feedback,
                score, BigDecimal.ONE, "graded", List.of());
    }

    private GradingResult gradeShortAnswerWithAi(Long courseId, Question question, String userAnswer) {
        List<RetrievedChunk> chunks = retrieveGradingContext(courseId, question, userAnswer);
        String prompt = buildShortAnswerGradingPrompt(question, userAnswer, chunks);
        String response = chatClient.call(prompt);
        AiGradingResponse parsed = parseGradingResponse(response);
        BigDecimal score = parsed.correct() ? BigDecimal.ONE : BigDecimal.ZERO;
        return new GradingResult(parsed.correct(), Question.GRADING_AI, parsed.feedback(),
                score, BigDecimal.ONE, "ai_graded", List.of());
    }

    private GradingResult gradeCompositeAnswer(Question question, String answerPayload) {
        try {
            JsonNode payload = objectMapper.readTree(answerPayload);
            JsonNode data = objectMapper.readTree(question.getQuestionData());
            JsonNode subQuestions = data.path("subQuestions");
            if (!payload.path("answers").isArray() || !subQuestions.isArray()) {
                throw new IllegalArgumentException("answerPayload.answers 和 questionData.subQuestions 必须是数组");
            }
            Map<String, String> submitted = new HashMap<>();
            for (JsonNode answer : payload.path("answers")) {
                String key = answer.path("subQuestionKey").asText();
                if (key.isBlank() && answer.has("subQuestionIndex")) {
                    key = "q" + (answer.path("subQuestionIndex").asInt() + 1);
                }
                if (!key.isBlank()) submitted.put(key, answer.path("answer").asText(""));
            }
            List<PracticeSubResult> results = new ArrayList<>();
            BigDecimal score = BigDecimal.ZERO;
            BigDecimal maxScore = BigDecimal.ZERO;
            int pending = 0;
            int index = 0;
            for (JsonNode sub : subQuestions) {
                String key = sub.path("key").asText("q" + (index + 1));
                String type = sub.path("type").asText();
                String strategy = sub.path("gradingStrategy").asText(defaultSubStrategy(type));
                BigDecimal itemMax = sub.has("maxScore")
                        ? sub.path("maxScore").decimalValue() : BigDecimal.ONE;
                maxScore = maxScore.add(itemMax);
                PracticeSubResult result = new PracticeSubResult();
                result.setSubQuestionKey(key);
                result.setReferenceAnswer(sub.path("answer").asText(null));
                result.setExplanation(sub.path("explanation").asText(null));
                result.setMaxScore(itemMax);
                if (Question.GRADING_RULE.equals(strategy)) {
                    String expected = normalizeAnswer(type, sub.path("answer").asText(""));
                    String actual = normalizeAnswer(type, submitted.getOrDefault(key, ""));
                    boolean correct = !expected.isBlank() && expected.equalsIgnoreCase(actual);
                    result.setCorrect(correct);
                    result.setScore(correct ? itemMax : BigDecimal.ZERO);
                    result.setGradingStatus("graded");
                    if (correct) score = score.add(itemMax);
                } else {
                    result.setCorrect(null);
                    result.setScore(null);
                    result.setGradingStatus("manual_required");
                    pending++;
                }
                results.add(result);
                index++;
            }
            String status = pending > 0 ? "manual_required" : "graded";
            Boolean correct = pending > 0 ? null : score.compareTo(maxScore) == 0;
            return new GradingResult(correct, pending > 0 ? Question.GRADING_MIXED : Question.GRADING_RULE,
                    pending > 0 ? "客观小题已判定，主观小题等待评价。" : "复合题已完成规则判题。",
                    score, maxScore, status, results);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("复合题答案格式无效", exception);
        }
    }

    private String defaultSubStrategy(String type) {
        return Set.of(Question.TYPE_SINGLE_CHOICE, Question.TYPE_MULTI_CHOICE,
                Question.TYPE_TRUE_FALSE, Question.TYPE_FILL_BLANK, Question.TYPE_LANGUAGE_BASIC).contains(type)
                ? Question.GRADING_RULE : Question.GRADING_MANUAL;
    }

    private List<RetrievedChunk> retrieveGradingContext(Long courseId, Question question, String userAnswer) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        if (question.getSourceChunkId() != null) {
            KnowledgeChunk source = chunkRepository.findById(question.getSourceChunkId());
            if (source != null) {
                chunks.add(toRetrievedChunk(source, 1.0));
            }
        }

        String query = String.join("\n",
                nullToEmpty(question.getStem()),
                nullToEmpty(question.getAnswer()),
                nullToEmpty(question.getExplanation()),
                nullToEmpty(userAnswer));
        try {
            for (RetrievedChunk chunk : knowledgeRetriever.retrieve(courseId, query, gradingTopK)) {
                boolean exists = chunks.stream().anyMatch(existing -> existing.chunkId().equals(chunk.chunkId()));
                if (!exists) {
                    chunks.add(chunk);
                }
            }
        } catch (RuntimeException ignored) {
            for (KnowledgeChunk chunk : chunkRepository.findByCourseId(courseId, gradingTopK)) {
                boolean exists = chunks.stream().anyMatch(existing -> existing.chunkId().equals(chunk.getId()));
                if (!exists) {
                    chunks.add(toRetrievedChunk(chunk, 0.0));
                }
            }
        }
        return chunks.stream().limit(gradingTopK).toList();
    }

    private RetrievedChunk toRetrievedChunk(KnowledgeChunk chunk, Double score) {
        return new RetrievedChunk(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getSourcePage(),
                score
        );
    }

    private String buildShortAnswerGradingPrompt(Question question, String userAnswer, List<RetrievedChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            context.append("[").append(i + 1).append("] source=")
                    .append(chunk.documentName() == null || chunk.documentName().isBlank() ? chunk.title() : chunk.documentName())
                    .append(chunk.sourcePage() == null ? "" : ", page=" + chunk.sourcePage())
                    .append("\n")
                    .append(abbreviate(chunk.content(), 1600)).append("\n\n");
        }
        if (context.length() == 0) {
            context.append("No retrieved course chunks were available.\n");
        }

        return """
                SHORT_ANSWER_GRADING_JSON
                You are grading a short-answer practice question for a course RAG study assistant.
                Use the retrieved course material first, then the standard answer and explanation.
                Treat semantically equivalent student answers as correct even if wording differs.
                Mark the answer incorrect if it misses key points, contradicts the material, or is too vague.
                Return JSON only, with this exact shape:
                {"correct":true,"feedback":"brief reason in Chinese"}

                Question:
                %s

                Standard answer:
                %s

                Explanation:
                %s

                Student answer:
                %s

                Retrieved course material:
                %s
                """.formatted(
                nullToEmpty(question.getStem()),
                nullToEmpty(question.getAnswer()),
                nullToEmpty(question.getExplanation()),
                nullToEmpty(userAnswer),
                context
        );
    }

    private AiGradingResponse parseGradingResponse(String response) {
        try {
            String json = extractJsonObject(response);
            JsonNode root = objectMapper.readTree(json);
            if (!root.has("correct")) {
                throw new IllegalArgumentException("missing correct");
            }
            boolean correct = root.path("correct").asBoolean(false);
            String feedback = root.path("feedback").asText("");
            if (feedback.isBlank()) {
                feedback = correct ? "AI grading: answer is acceptable." : "AI grading: answer is incomplete or incorrect.";
            }
            return new AiGradingResponse(correct, feedback);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI grading response is not valid JSON: " + abbreviate(response, 500));
        }
    }

    private String extractJsonObject(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return text;
        }
        return text.substring(start, end + 1);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String choiceLetters(String value) {
        TreeSet<Character> letters = new TreeSet<>();
        for (char ch : value.toUpperCase().toCharArray()) {
            if (ch >= 'A' && ch <= 'D') {
                letters.add(ch);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (Character letter : letters) {
            builder.append(letter);
        }
        return builder.toString();
    }

    private record GradingResult(Boolean correct, String mode, String feedback,
                                 BigDecimal score, BigDecimal maxScore, String status,
                                 List<PracticeSubResult> subResults) {
    }

    private record AiGradingResponse(boolean correct, String feedback) {
    }
}
