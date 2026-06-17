package com.rag.studyassistant.document.model;

import java.time.LocalDateTime;

/**
 * 文档响应体
 */
public class DocumentResponse {

    private Long id;
    private Long courseId;
    private String filename;
    private String fileType;
    private String filePath;
    private String parseStatus;
    private Integer chunkCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DocumentResponse from(CourseDocument doc) {
        DocumentResponse r = new DocumentResponse();
        r.id = doc.getId();
        r.courseId = doc.getCourseId();
        r.filename = doc.getFilename();
        r.fileType = doc.getFileType();
        r.filePath = doc.getFilePath();
        r.parseStatus = doc.getParseStatus();
        r.chunkCount = doc.getChunkCount();
        r.createdAt = doc.getCreatedAt();
        r.updatedAt = doc.getUpdatedAt();
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
