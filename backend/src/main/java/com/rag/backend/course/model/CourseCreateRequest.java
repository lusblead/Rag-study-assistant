package com.rag.backend.course.model;

/**
 * 创建课程请求体
 */
public class CourseCreateRequest {

    private String name;
    private String description;
    private String term;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
}
