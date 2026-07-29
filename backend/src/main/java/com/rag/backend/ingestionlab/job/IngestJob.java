// 持久化执行权，用 owner、期限和版本阻止并发误提交。
package com.rag.backend.ingestionlab.job;

import java.time.LocalDateTime;

// 持久化 Job 行：描述一次执行尝试，不与 Version 生命周期混用。
public class IngestJob {
    // jobId 标识逻辑任务；documentVersionId/jobType 把任务绑定到具体文档版本与处理类型。
    private String jobId;
    private Long documentVersionId;
    private String jobType;
    // state 是任务生命周期；attempt/maxAttempts 共同限制失败重试次数。
    private String state;
    private Integer attempt;
    private Integer maxAttempts;
    // leaseOwner/leaseUntil 描述当前执行权及有效期；过期后其他 Worker 才能接管。
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    // nextRunAt 把退避时间持久化，进程重启后仍知道最早何时可重试。
    private LocalDateTime nextRunAt;
    // stateVersion 参与 CAS，阻止持有旧快照的 Worker 覆盖新 owner 的状态。
    private Long stateVersion;
    // 只持久化稳定错误码和脱敏摘要，不把异常堆栈或文档原文写入任务表。
    private String errorCode;
    private String errorDetailDigest;

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public Long getDocumentVersionId() { return documentVersionId; }
    public void setDocumentVersionId(Long value) { this.documentVersionId = value; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public Long getStateVersion() { return stateVersion; }
    public void setStateVersion(Long stateVersion) { this.stateVersion = stateVersion; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorDetailDigest() { return errorDetailDigest; }
    public void setErrorDetailDigest(String value) { this.errorDetailDigest = value; }
}