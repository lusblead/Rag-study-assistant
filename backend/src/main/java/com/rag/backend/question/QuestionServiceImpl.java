package com.rag.backend.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.course.CourseMapper;
import com.rag.backend.practice.PracticeMapper;
import com.rag.backend.paper.PaperQuestionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import com.rag.backend.question.model.Question;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class QuestionServiceImpl implements QuestionService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> TYPES = Set.of(
            Question.TYPE_SINGLE_CHOICE, Question.TYPE_MULTI_CHOICE, Question.TYPE_TRUE_FALSE,
            Question.TYPE_SHORT_ANSWER, Question.TYPE_FILL_BLANK, Question.TYPE_COMPOSITION,
            Question.TYPE_CLASSICAL_CHINESE_READING, Question.TYPE_POETRY_APPRECIATION,
            Question.TYPE_MODERN_READING, Question.TYPE_TRANSLATION, Question.TYPE_SENTENCE_BREAK,
            Question.TYPE_EXPLANATION, Question.TYPE_LANGUAGE_BASIC
    );
    private static final Set<String> COMPOSITE_TYPES = Set.of(
            Question.TYPE_CLASSICAL_CHINESE_READING, Question.TYPE_POETRY_APPRECIATION,
            Question.TYPE_MODERN_READING
    );
    private static final Set<String> GRADING_STRATEGIES = Set.of(
            Question.GRADING_RULE, Question.GRADING_MANUAL, Question.GRADING_AI, Question.GRADING_MIXED
    );

    private final QuestionMapper questionMapper;
    private final CourseMapper courseMapper;
    private final PracticeMapper practiceMapper;
    private PaperQuestionMapper paperQuestionMapper;

    public QuestionServiceImpl(QuestionMapper questionMapper, CourseMapper courseMapper, PracticeMapper practiceMapper) {
        this.questionMapper = questionMapper;
        this.courseMapper = courseMapper;
        this.practiceMapper = practiceMapper;
    }

    @Override
    public Question save(Question question) {
        if (question.getCourseId() == null) {
            throw new IllegalArgumentException("course_id 不能为空");
        }
        if (courseMapper.selectById(question.getCourseId()) == null) {
            throw new IllegalArgumentException("课程不存在: " + question.getCourseId());
        }
        if (!StringUtils.hasText(question.getStem())) {
            throw new IllegalArgumentException("题干不能为空");
        }
        if (!StringUtils.hasText(question.getType())) {
            throw new IllegalArgumentException("题型不能为空");
        }
        question.setType(question.getType().trim().toLowerCase(Locale.ROOT));
        if (!TYPES.contains(question.getType())) {
            throw new IllegalArgumentException("不支持的题型: " + question.getType());
        }
        if (!Set.of(Question.DIFF_EASY, Question.DIFF_MEDIUM, Question.DIFF_HARD)
                .contains(question.getDifficulty())) {
            question.setDifficulty(Question.DIFF_MEDIUM);
        }
        String subject = StringUtils.hasText(question.getSubject())
                ? question.getSubject().trim().toLowerCase(Locale.ROOT)
                : defaultSubject(question.getType());
        if (!Set.of(Question.SUBJECT_GENERAL, Question.SUBJECT_CHINESE).contains(subject)) {
            throw new IllegalArgumentException("不支持的学科: " + subject);
        }
        question.setSubject(subject);
        String strategy = StringUtils.hasText(question.getGradingStrategy())
                ? question.getGradingStrategy().trim().toLowerCase(Locale.ROOT)
                : defaultGradingStrategy(question.getType());
        if (!GRADING_STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("不支持的判题策略: " + strategy);
        }
        question.setGradingStrategy(strategy);
        question.setChapterTags(normalizeChapterTags(question.getChapterTags()));
        question.setQuestionData(normalizeJsonObject(question.getQuestionData(), "questionData", false));
        question.setAnswerSchema(normalizeJsonObject(question.getAnswerSchema(), "answerSchema", false));
        if (COMPOSITE_TYPES.contains(question.getType())) {
            JsonNode data = readJson(question.getQuestionData(), "questionData");
            if (!data.path("material").isObject() || !data.path("subQuestions").isArray()
                    || data.path("subQuestions").isEmpty()) {
                throw new IllegalArgumentException("复合题 questionData 必须包含 material 和非空 subQuestions");
            }
        }
        if (Question.TYPE_COMPOSITION.equals(question.getType())) {
            JsonNode data = readJson(question.getQuestionData(), "questionData");
            if (!data.path("requirements").isObject()) {
                throw new IllegalArgumentException("作文题 questionData 必须包含 requirements");
            }
        }
        if (question.getAnswer() == null) question.setAnswer("");

        questionMapper.insert(question);
        return question;
    }

    /**
     * 批量保存题目 —— 供同学C的AI出题模块调用。
     *
     * AI 生成题目的 JSON 字段约定：
     *   type:     "single_choice" | "multi_choice" | "true_false" | "short_answer"
     *   stem:     题干文本
     *   options:  JSON 字符串，如 '["A.选项1","B.选项2","C.选项3","D.选项4"]'
     *             判断题可传 '["正确","错误"]' 或 null
     *   answer:   答案文本，如 "A" / "AB" / "正确" / "这是简答题答案"
     *   explanation: 解析文本
     *   difficulty:   "easy" | "medium" | "hard"
     *   knowledgePoint: 知识点名称
     *   chapterTags: JSON 数组，如 '["第1章","第3章"]'，可同时标注多个章节
     *   sourceChunkId:  来源知识片段ID（可为 null）
     */
    @Override
    public List<Question> batchSave(List<Question> questions) {
        List<Question> saved = new ArrayList<>();
        for (Question q : questions) {
            saved.add(save(q));
        }
        return saved;
    }

    @Override
    public List<Question> listByCourse(Long courseId, String type, String difficulty) {
        return listByCourse(courseId, type, difficulty, null);
    }

    @Autowired
    public void setPaperQuestionMapper(PaperQuestionMapper paperQuestionMapper) { this.paperQuestionMapper = paperQuestionMapper; }

    @Override
    public Question update(Long id, Question question) {
        Question existing = id == null ? null : questionMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("题目不存在: " + id);
        if (question == null || !StringUtils.hasText(question.getStem())) throw new IllegalArgumentException("题干不能为空");
        question.setId(id); question.setCourseId(existing.getCourseId()); question.setSourceChunkId(existing.getSourceChunkId()); question.setBatchId(existing.getBatchId());
        question.setType(StringUtils.hasText(question.getType()) ? question.getType().trim().toLowerCase(Locale.ROOT) : existing.getType());
        if (!TYPES.contains(question.getType())) throw new IllegalArgumentException("不支持的题型: " + question.getType());
        question.setDifficulty(Set.of(Question.DIFF_EASY,Question.DIFF_MEDIUM,Question.DIFF_HARD).contains(question.getDifficulty()) ? question.getDifficulty() : existing.getDifficulty());
        question.setSubject(StringUtils.hasText(question.getSubject()) ? question.getSubject() : existing.getSubject());
        question.setGradingStrategy(StringUtils.hasText(question.getGradingStrategy()) ? question.getGradingStrategy() : existing.getGradingStrategy());
        question.setOptions(question.getOptions()!=null?question.getOptions():existing.getOptions()); question.setAnswer(question.getAnswer()!=null?question.getAnswer():existing.getAnswer());
        question.setExplanation(question.getExplanation()!=null?question.getExplanation():existing.getExplanation()); question.setKnowledgePoint(question.getKnowledgePoint()!=null?question.getKnowledgePoint():existing.getKnowledgePoint());
        question.setChapterTags(question.getChapterTags()!=null?normalizeChapterTags(question.getChapterTags()):existing.getChapterTags());
        question.setQuestionData(question.getQuestionData()!=null?normalizeJsonObject(question.getQuestionData(),"questionData",false):existing.getQuestionData());
        question.setAnswerSchema(question.getAnswerSchema()!=null?normalizeJsonObject(question.getAnswerSchema(),"answerSchema",false):existing.getAnswerSchema());
        if (COMPOSITE_TYPES.contains(question.getType())) { JsonNode data=readJson(question.getQuestionData(),"questionData"); if(!data.path("material").isObject()||!data.path("subQuestions").isArray()||data.path("subQuestions").isEmpty()) throw new IllegalArgumentException("复合题 questionData 必须包含 material 和非空 subQuestions"); }
        questionMapper.update(question); return questionMapper.selectById(id);
    }

    @Override
    public List<Question> listByCourse(Long courseId, String type, String difficulty, String subject) {
        return questionMapper.selectListByCourse(courseId,
                StringUtils.hasText(type) ? type : null,
                StringUtils.hasText(difficulty) ? difficulty : null,
                StringUtils.hasText(subject) ? subject : null);
    }

    private String normalizeChapterTags(String value) {
        if (!StringUtils.hasText(value)) return "[]";
        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (!node.isArray()) throw new IllegalArgumentException("chapterTags 必须是 JSON 数组");
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("chapterTags 必须是有效的 JSON 数组", exception);
        }
    }

    private String normalizeJsonObject(String value, String field, boolean required) {
        if (!StringUtils.hasText(value)) {
            if (required) throw new IllegalArgumentException(field + " 不能为空");
            return null;
        }
        JsonNode node = readJson(value, field);
        if (!node.isObject()) throw new IllegalArgumentException(field + " 必须是 JSON 对象");
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException(field + " 无法序列化", exception);
        }
    }

    private JsonNode readJson(String value, String field) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + " 不能为空");
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(field + " 必须是有效 JSON", exception);
        }
    }

    private String defaultSubject(String type) {
        return Set.of(Question.TYPE_FILL_BLANK, Question.TYPE_COMPOSITION,
                Question.TYPE_CLASSICAL_CHINESE_READING, Question.TYPE_POETRY_APPRECIATION,
                Question.TYPE_MODERN_READING, Question.TYPE_TRANSLATION, Question.TYPE_SENTENCE_BREAK,
                Question.TYPE_EXPLANATION, Question.TYPE_LANGUAGE_BASIC).contains(type)
                ? Question.SUBJECT_CHINESE : Question.SUBJECT_GENERAL;
    }

    private String defaultGradingStrategy(String type) {
        if (COMPOSITE_TYPES.contains(type)) return Question.GRADING_MIXED;
        if (Set.of(Question.TYPE_COMPOSITION, Question.TYPE_TRANSLATION,
                Question.TYPE_EXPLANATION, Question.TYPE_SENTENCE_BREAK).contains(type)) {
            return Question.GRADING_MANUAL;
        }
        if (Question.TYPE_SHORT_ANSWER.equals(type)) return Question.GRADING_AI;
        return Question.GRADING_RULE;
    }

    @Override
    public void delete(Long id) {
        if (id == null || questionMapper.selectById(id) == null) {
            throw new IllegalArgumentException("题目不存在: " + id);
        }
        practiceMapper.deleteByQuestionId(id);
        if (paperQuestionMapper != null) paperQuestionMapper.deleteByQuestionId(id);
        questionMapper.deleteById(id);
    }
}
