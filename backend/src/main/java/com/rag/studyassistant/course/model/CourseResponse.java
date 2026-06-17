package com.rag.studyassistant.course.model;

import java.time.LocalDateTime;

/**
 * 课程响应体（可扩展字段，当前与实体一致）
 */
public class CourseResponse {

    private Long id;
    private String name;
    private String description;
    private String term;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CourseResponse from(Course course) {
        CourseResponse r = new CourseResponse();
        r.id = course.getId();
        r.name = course.getName();
        r.description = course.getDescription();
        r.term = course.getTerm();
        r.createdAt = course.getCreatedAt();
        r.updatedAt = course.getUpdatedAt();
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
