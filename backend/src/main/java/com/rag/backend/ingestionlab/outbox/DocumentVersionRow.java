// 唯一键负责去重，owner/stateVersion/期限条件负责并发提交权。
package com.rag.backend.ingestionlab.outbox;

// DocumentVersionRow：数据库映射模型，保存可恢复协议的持久化事实。
public class DocumentVersionRow {
    private Long id;
    private Long documentId;
    private Integer versionNo;
    private String contentHash;
    private String pipelineFingerprint;
    private String state;
    private Long stateVersion;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getPipelineFingerprint() { return pipelineFingerprint; }
    public void setPipelineFingerprint(String value) { this.pipelineFingerprint = value; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Long getStateVersion() { return stateVersion; }
    public void setStateVersion(Long stateVersion) { this.stateVersion = stateVersion; }
}