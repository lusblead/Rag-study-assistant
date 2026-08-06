package com.rag.backend.agent.model;

import java.time.LocalDateTime;

// 表示课程资料切片后的知识片段记录。
public class KnowledgeChunk {

    private Long id;

    private Long courseId;

    private Long documentId;

    /** 可靠摄取批次身份；在线检索必须与当前 activeVersionIds 再次比对。 */
    private Long documentVersionId;

    private Integer chunkIndex;

    private String title;

    private String content;

    private Integer sourcePage;

    private Integer tokenCount;

    private String milvusVectorId;

    private String embeddingStatus;

    private String chunkBusinessKey;

    private String contentHash;

    private Long vectorBusinessId;

    private String embeddingModel;

    private Integer embeddingDimension;

    private String embeddingErrorCode;

    private LocalDateTime createdAt;

    // -- embedding status constants ----------------------------------

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE    = "DONE";
    public static final String STATUS_FAILED  = "FAILED";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public Long getDocumentVersionId() { return documentVersionId; }
    public void setDocumentVersionId(Long documentVersionId) { this.documentVersionId = documentVersionId; }

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

    public String getChunkBusinessKey() { return chunkBusinessKey; }
    public void setChunkBusinessKey(String chunkBusinessKey) { this.chunkBusinessKey = chunkBusinessKey; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Long getVectorBusinessId() { return vectorBusinessId; }
    public void setVectorBusinessId(Long vectorBusinessId) { this.vectorBusinessId = vectorBusinessId; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public Integer getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(Integer embeddingDimension) { this.embeddingDimension = embeddingDimension; }

    public String getEmbeddingErrorCode() { return embeddingErrorCode; }
    public void setEmbeddingErrorCode(String embeddingErrorCode) { this.embeddingErrorCode = embeddingErrorCode; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
