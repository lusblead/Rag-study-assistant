package com.rag.backend.agent.retrieval;

/** MyBatis FULLTEXT 查询返回的轻量候选行。 */
public class LexicalCandidateRow {
    private Long documentVersionId;
    public Long getDocumentVersionId() { return documentVersionId; }
    public void setDocumentVersionId(Long value) { documentVersionId = value; }
    private Long chunkId;
    private Long documentId;
    private String documentName;
    private String title;
    private String content;
    private Integer sourcePage;
    private Double rawScore;

    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getSourcePage() { return sourcePage; }
    public void setSourcePage(Integer sourcePage) { this.sourcePage = sourcePage; }
    public Double getRawScore() { return rawScore; }
    public void setRawScore(Double rawScore) { this.rawScore = rawScore; }
}
