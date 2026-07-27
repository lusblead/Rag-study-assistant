package com.rag.backend.question.model;

import java.time.LocalDateTime;

public class Question {

    private Long id;

    private Long courseId;

    private Long sourceChunkId;

    /** 查询时由来源知识片段关联得到，不单独写入 questions 表。 */
    private Long sourceDocumentId;

    private Long batchId;

    private String type;

    private String stem;

    /** JSON 字符串，如 ["A.选项1","B.选项2","C.选项3","D.选项4"] */
    private String options;

    private String answer;

    private String explanation;

    private String difficulty;

    private String knowledgePoint;

    /** JSON 数组；一道题可同时属于多个章节。 */
    private String chapterTags;

    /** 复杂题材料、要求和小题的 JSON 对象。 */
    private String questionData;

    /** 结构化参考答案、评分点和评分量表的 JSON 对象。 */
    private String answerSchema;

    private String subject;

    private String gradingStrategy;

    private LocalDateTime createdAt;

    // -- type constants --------------------------------------------
    public static final String TYPE_SINGLE_CHOICE = "single_choice";
    public static final String TYPE_MULTI_CHOICE  = "multi_choice";
    public static final String TYPE_TRUE_FALSE    = "true_false";
    public static final String TYPE_SHORT_ANSWER  = "short_answer";
    public static final String TYPE_FILL_BLANK = "fill_blank";
    public static final String TYPE_COMPOSITION = "composition";
    public static final String TYPE_CLASSICAL_CHINESE_READING = "classical_chinese_reading";
    public static final String TYPE_POETRY_APPRECIATION = "poetry_appreciation";
    public static final String TYPE_MODERN_READING = "modern_reading";
    public static final String TYPE_TRANSLATION = "translation";
    public static final String TYPE_SENTENCE_BREAK = "sentence_break";
    public static final String TYPE_EXPLANATION = "explanation";
    public static final String TYPE_LANGUAGE_BASIC = "language_basic";

    public static final String SUBJECT_GENERAL = "general";
    public static final String SUBJECT_CHINESE = "chinese";

    public static final String GRADING_RULE = "rule";
    public static final String GRADING_MANUAL = "manual";
    public static final String GRADING_AI = "ai";
    public static final String GRADING_MIXED = "mixed";

    // -- difficulty constants --------------------------------------
    public static final String DIFF_EASY   = "easy";
    public static final String DIFF_MEDIUM = "medium";
    public static final String DIFF_HARD   = "hard";

    // -- getters / setters ----------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Long getSourceChunkId() { return sourceChunkId; }
    public void setSourceChunkId(Long sourceChunkId) { this.sourceChunkId = sourceChunkId; }

    public Long getSourceDocumentId() { return sourceDocumentId; }
    public void setSourceDocumentId(Long sourceDocumentId) { this.sourceDocumentId = sourceDocumentId; }

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStem() { return stem; }
    public void setStem(String stem) { this.stem = stem; }

    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }

    public String getChapterTags() { return chapterTags; }
    public void setChapterTags(String chapterTags) { this.chapterTags = chapterTags; }

    public String getQuestionData() { return questionData; }
    public void setQuestionData(String questionData) { this.questionData = questionData; }

    public String getAnswerSchema() { return answerSchema; }
    public void setAnswerSchema(String answerSchema) { this.answerSchema = answerSchema; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getGradingStrategy() { return gradingStrategy; }
    public void setGradingStrategy(String gradingStrategy) { this.gradingStrategy = gradingStrategy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
