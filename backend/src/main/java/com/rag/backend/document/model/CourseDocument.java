package com.rag.backend.document.model;

import java.time.LocalDateTime;

public class CourseDocument {

    private Long id;

    private Long courseId;

    private String filename;

    private String fileType;

    private String filePath;

    private String parseStatus;

    private Integer chunkCount;

    /** 当前允许在线检索的版本；新版未核验通过前不会覆盖该指针。 */
    private Long activeVersionId;

    /** ACTIVE / DELETING / DELETED，控制文档整体可见性。 */
    private String lifecycleStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // -- parse status constants -----------------------------------
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_PARSED  = "PARSED";
    public static final String STATUS_FAILED  = "FAILED";

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

    public Long getActiveVersionId() { return activeVersionId; }
    public void setActiveVersionId(Long activeVersionId) { this.activeVersionId = activeVersionId; }

    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
