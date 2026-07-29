// 让业务状态与事件原子落库，并为步骤建立幂等记录。
package com.rag.backend.ingestionlab.outbox;

import java.time.LocalDateTime;

// OutboxEvent：与 Version/Job 同事务落库的待发布事件行。
public class OutboxEvent {
    // eventId 是事件主键；aggregateType/aggregateId 指向需要被唤醒的 Ingest Job。
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    // eventType 决定 Dispatcher 的处理器；payloadJson 只携带重放所需稳定标识。
    private String eventType;
    private String payloadJson;
    // status/attempts/availableAt 描述至少一次派发的生命周期与退避时间。
    private String status;
    private Integer attempts;
    private LocalDateTime availableAt;
    // claimedBy/claimUntil 是 Dispatcher 短租约，避免多个实例同时发布同一事件。
    private String claimedBy;
    private LocalDateTime claimUntil;
    // stateVersion 让 claim 成为 CAS；读取旧事件快照的实例无法再次取得所有权。
    private Long stateVersion;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String value) { this.aggregateType = value; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String value) { this.aggregateId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { this.eventType = value; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String value) { this.payloadJson = value; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public LocalDateTime getAvailableAt() { return availableAt; }
    public void setAvailableAt(LocalDateTime value) { this.availableAt = value; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String value) { this.claimedBy = value; }
    public LocalDateTime getClaimUntil() { return claimUntil; }
    public void setClaimUntil(LocalDateTime value) { this.claimUntil = value; }
    public Long getStateVersion() { return stateVersion; }
    public void setStateVersion(Long value) { this.stateVersion = value; }
}