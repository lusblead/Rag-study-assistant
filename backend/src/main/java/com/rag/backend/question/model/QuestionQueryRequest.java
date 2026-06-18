package com.rag.backend.question.model;

/**
 * 题目查询请求参数
 */
public class QuestionQueryRequest {

    private Long courseId;
    private String type;
    private String difficulty;

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
}
