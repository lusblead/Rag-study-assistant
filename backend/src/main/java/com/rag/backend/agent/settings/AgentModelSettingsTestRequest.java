package com.rag.backend.agent.settings;

// 承载模型或 Embedding 连通性测试请求。
public class AgentModelSettingsTestRequest extends AgentModelSettingsRequest {
    private String target;

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
}
