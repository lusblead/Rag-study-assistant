package com.rag.studyassistant.question.model;

import java.time.LocalDateTime;

/**
 * 题目响应体
 */
public class QuestionResponse {

    private Long id;
    private Long courseId;
    private Long sourceChunkId;
    private String type;
    private String stem;
    private String options;
    private String answer;
    private String explanation;
    private String difficulty;
    private String knowledgePoint;
    private LocalDateTime createdAt;

    public static QuestionResponse from(Question q) {
        QuestionResponse r = new QuestionResponse();
        r.id = q.getId();
        r.courseId = q.getCourseId();
        r.sourceChunkId = q.getSourceChunkId();
        r.type = q.getType();
        r.stem = q.getStem();
        r.options = q.getOptions();
        r.answer = q.getAnswer();
        r.explanation = q.getExplanation();
        r.difficulty = q.getDifficulty();
        r.knowledgePoint = q.getKnowledgePoint();
        r.createdAt = q.getCreatedAt();
        return r;
    }

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
