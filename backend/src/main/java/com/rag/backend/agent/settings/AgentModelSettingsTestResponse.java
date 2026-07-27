package com.rag.backend.agent.settings;

// 承载模型或 Embedding 连通性测试结果。
public class AgentModelSettingsTestResponse {
    private String target;
    private boolean success;
    private String message;
    private Integer statusCode;
    private long latencyMs;

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
}
