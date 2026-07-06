package com.rag.backend.question.model;

import java.time.LocalDateTime;

public class QuestionBatch {
    private Long id;
    private Long courseId;
    private String title;
    private String mode;
    private String requirement;
    private Integer questionCount;
    private String questionType;
    private String difficulty;
    private Boolean referenceRealQuestions;
    private String styleSummary;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public Integer getQuestionCount() { return questionCount; }
    public void setQuestionCount(Integer questionCount) { this.questionCount = questionCount; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public Boolean getReferenceRealQuestions() { return referenceRealQuestions; }
    public void setReferenceRealQuestions(Boolean referenceRealQuestions) { this.referenceRealQuestions = referenceRealQuestions; }
    public String getStyleSummary() { return styleSummary; }
    public void setStyleSummary(String styleSummary) { this.styleSummary = styleSummary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
