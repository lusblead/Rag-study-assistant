package com.rag.studyassistant.practice.model;

import java.time.LocalDateTime;

/**
 * 练习结果响应体
 */
public class PracticeResultResponse {

    private Long id;
    private Long courseId;
    private Long questionId;
    private String userAnswer;
    private String correctAnswer;
    private Boolean isCorrect;
    private LocalDateTime createdAt;

    public static PracticeResultResponse from(PracticeRecord record, String correctAnswer) {
        PracticeResultResponse r = new PracticeResultResponse();
        r.id = record.getId();
        r.courseId = record.getCourseId();
        r.questionId = record.getQuestionId();
        r.userAnswer = record.getUserAnswer();
        r.correctAnswer = correctAnswer;
        r.isCorrect = record.getIsCorrect();
        r.createdAt = record.getCreatedAt();
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getUserAnswer() { return userAnswer; }
    public void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }

    public String getCorrectAnswer() { return correctAnswer; }
    public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }

    public Boolean getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Boolean isCorrect) { this.isCorrect = isCorrect; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
