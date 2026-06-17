package com.rag.studyassistant.document.model;

/**
 * 文档上传请求体（用于接口参数说明，实际上传使用 multipart/form-data）
 */
public class DocumentUploadRequest {

    private Long courseId;

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
}
