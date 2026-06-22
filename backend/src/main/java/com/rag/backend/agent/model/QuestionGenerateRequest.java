package com.rag.backend.agent.model;

// 承载 AI 出题接口的请求参数。
public class QuestionGenerateRequest {
    private Long courseId;
    private String requirement;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public String getRequirement() {
        return requirement;
    }

    public void setRequirement(String requirement) {
        this.requirement = requirement;
    }
}