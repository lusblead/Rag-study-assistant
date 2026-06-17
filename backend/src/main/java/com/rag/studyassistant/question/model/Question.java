package com.rag.studyassistant.question.model;

import java.time.LocalDateTime;

public class Question {

    private Long id;

    private Long courseId;

    private Long sourceChunkId;

    private String type;

    private String stem;

    /** JSON 字符串，如 ["A.选项1","B.选项2","C.选项3","D.选项4"] */
    private String options;

    private String answer;

    private String explanation;

    private String difficulty;

    private String knowledgePoint;

    private LocalDateTime createdAt;

    // -- type constants --------------------------------------------
    public static final String TYPE_SINGLE_CHOICE = "single_choice";
    public static final String TYPE_MULTI_CHOICE  = "multi_choice";
    public static final String TYPE_TRUE_FALSE    = "true_false";
    public static final String TYPE_SHORT_ANSWER  = "short_answer";

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
