package com.rag.backend.agent.model;

// 承载 RAG 问答接口的请求参数。
public class RagChatRequest {
    private Long courseId;
    private Long sessionId;
    private String question;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
