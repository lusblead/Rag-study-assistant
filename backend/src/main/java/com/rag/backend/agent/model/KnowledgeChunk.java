package com.rag.backend.agent.model;

import java.time.LocalDateTime;

public class KnowledgeChunk {

    private Long id;

    private Long courseId;

    private Long documentId;

    private Integer chunkIndex;

    private String title;

    private String content;

    private Integer sourcePage;

    private Integer tokenCount;

    private String milvusVectorId;

    private String embeddingStatus;

    private LocalDateTime createdAt;

    // -- embedding status constants ----------------------------------

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE    = "DONE";
    public static final String STATUS_FAILED  = "FAILED";

    // -- getters / setters -------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSourcePage() { return sourcePage; }
    public void setSourcePage(Integer sourcePage) { this.sourcePage = sourcePage; }

    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }

    public String getMilvusVectorId() { return milvusVectorId; }
    public void setMilvusVectorId(String milvusVectorId) { this.milvusVectorId = milvusVectorId; }

    public String getEmbeddingStatus() { return embeddingStatus; }
    public void setEmbeddingStatus(String embeddingStatus) { this.embeddingStatus = embeddingStatus; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
