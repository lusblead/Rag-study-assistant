package com.rag.backend.practice.model;

import java.math.BigDecimal;

public class PracticeSubResult {
    private String subQuestionKey;
    private Boolean correct;
    private String referenceAnswer;
    private String explanation;
    private BigDecimal score;
    private BigDecimal maxScore;
    private String gradingStatus;

    public String getSubQuestionKey() { return subQuestionKey; }
    public void setSubQuestionKey(String subQuestionKey) { this.subQuestionKey = subQuestionKey; }
    public Boolean getCorrect() { return correct; }
    public void setCorrect(Boolean correct) { this.correct = correct; }
    public String getReferenceAnswer() { return referenceAnswer; }
    public void setReferenceAnswer(String referenceAnswer) { this.referenceAnswer = referenceAnswer; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
    public String getGradingStatus() { return gradingStatus; }
    public void setGradingStatus(String gradingStatus) { this.gradingStatus = gradingStatus; }
}
