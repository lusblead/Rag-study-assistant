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
import com.rag.backend.question.QuestionMapper;
import com.rag.backend.question.model.Question;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
        if (courseMapper.selectById(courseId) == null) {
            throw new IllegalArgumentException("Course does not exist: " + courseId);
        }

        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("Question does not exist: " + questionId);
        }

        GradingResult grading = gradeAnswer(courseId, question, userAnswer);

        PracticeRecord record = new PracticeRecord();
        record.setCourseId(courseId);
        record.setQuestionId(questionId);
        record.setUserAnswer(userAnswer);
        record.setIsCorrect(grading.correct());
        record.setGradingMode(grading.mode());
        record.setGradingFeedback(grading.feedback());

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

        String standardAnswer = normalizeAnswer(question.getType(), question.getAnswer());
        String submittedAnswer = normalizeAnswer(question.getType(), userAnswer);
        boolean isCorrect = !standardAnswer.isBlank() && standardAnswer.equalsIgnoreCase(submittedAnswer);
        String feedback = isCorrect ? "Rule grading: answer matches the standard answer."
                : "Rule grading: answer does not match the standard answer.";
        return new GradingResult(isCorrect, "rule", feedback);
    }

    private GradingResult gradeShortAnswerWithAi(Long courseId, Question question, String userAnswer) {
        List<RetrievedChunk> chunks = retrieveGradingContext(courseId, question, userAnswer);
        String prompt = buildShortAnswerGradingPrompt(question, userAnswer, chunks);
        String response = chatClient.call(prompt);
        AiGradingResponse parsed = parseGradingResponse(response);
        return new GradingResult(parsed.correct(), "ai", parsed.feedback());
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
                score
        );
    }

    private String buildShortAnswerGradingPrompt(Question question, String userAnswer, List<RetrievedChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            context.append("[").append(i + 1).append("] chunkId=").append(chunk.chunkId())
                    .append(", documentId=").append(chunk.documentId()).append("\n")
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

    private record GradingResult(boolean correct, String mode, String feedback) {
    }

    private record AiGradingResponse(boolean correct, String feedback) {
    }
}
