package com.rag.backend.agent.model;
import lombok.Data;

import java.time.LocalDateTime;

@Data
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
}
