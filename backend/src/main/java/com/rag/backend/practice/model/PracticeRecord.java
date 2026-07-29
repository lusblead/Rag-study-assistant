package com.rag.backend.practice.model;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

public class PracticeRecord {

    private Long id;

    private Long courseId;

    private Long questionId;

    private String userAnswer;

    private Boolean isCorrect;

    private String gradingMode;

    private String gradingFeedback;

    private String answerPayload;
    private BigDecimal score;
    private BigDecimal maxScore;
    private String gradingStatus;
    private List<PracticeSubResult> subResults;

    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getUserAnswer() { return userAnswer; }
    public void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }

    public Boolean getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Boolean isCorrect) { this.isCorrect = isCorrect; }

    public String getGradingMode() { return gradingMode; }
    public void setGradingMode(String gradingMode) { this.gradingMode = gradingMode; }

    public String getGradingFeedback() { return gradingFeedback; }
    public void setGradingFeedback(String gradingFeedback) { this.gradingFeedback = gradingFeedback; }

    public String getAnswerPayload() { return answerPayload; }
    public void setAnswerPayload(String answerPayload) { this.answerPayload = answerPayload; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
    public String getGradingStatus() { return gradingStatus; }
    public void setGradingStatus(String gradingStatus) { this.gradingStatus = gradingStatus; }
    public List<PracticeSubResult> getSubResults() { return subResults; }
    public void setSubResults(List<PracticeSubResult> subResults) { this.subResults = subResults; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
