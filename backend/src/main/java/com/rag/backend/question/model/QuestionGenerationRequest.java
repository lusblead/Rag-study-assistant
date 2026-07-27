package com.rag.backend.question.model;

import java.util.List;

public class QuestionGenerationRequest {
    private Long courseId;
    private Integer count;
    private String type;
    private String difficulty;
    private String requirement;
    private String mode;
    private String title;
    private List<Long> documentIds;
    private Boolean referenceRealQuestions;
    private List<Long> styleDocumentIds;
    private String subject;
    private List<String> questionTypes;

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }
    public Boolean getReferenceRealQuestions() { return referenceRealQuestions; }
    public void setReferenceRealQuestions(Boolean referenceRealQuestions) { this.referenceRealQuestions = referenceRealQuestions; }
    public List<Long> getStyleDocumentIds() { return styleDocumentIds; }
    public void setStyleDocumentIds(List<Long> styleDocumentIds) { this.styleDocumentIds = styleDocumentIds; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public List<String> getQuestionTypes() { return questionTypes; }
    public void setQuestionTypes(List<String> questionTypes) { this.questionTypes = questionTypes; }
}
