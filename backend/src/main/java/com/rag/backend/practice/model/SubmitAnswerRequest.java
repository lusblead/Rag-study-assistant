package com.rag.backend.practice.model;

/**
 * 提交答案请求体
 */
public class SubmitAnswerRequest {

    private Long courseId;
    private Long questionId;
    private String userAnswer;

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }

    public String getUserAnswer() { return userAnswer; }
    public void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }
}
