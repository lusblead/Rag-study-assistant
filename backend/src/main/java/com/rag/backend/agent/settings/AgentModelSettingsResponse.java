package com.rag.backend.agent.settings;

// 承载返回给前端的模型配置摘要。
public class AgentModelSettingsResponse {
    private String llmProvider;
    private String llmBaseUrl;
    private String llmModel;
    private boolean llmApiKeySet;
    private String embeddingProvider;
    private String embeddingBaseUrl;
    private String embeddingModel;
    private boolean embeddingApiKeySet;

    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }

    public String getLlmBaseUrl() { return llmBaseUrl; }
    public void setLlmBaseUrl(String llmBaseUrl) { this.llmBaseUrl = llmBaseUrl; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public boolean isLlmApiKeySet() { return llmApiKeySet; }
    public void setLlmApiKeySet(boolean llmApiKeySet) { this.llmApiKeySet = llmApiKeySet; }

    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }

    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public boolean isEmbeddingApiKeySet() { return embeddingApiKeySet; }
    public void setEmbeddingApiKeySet(boolean embeddingApiKeySet) { this.embeddingApiKeySet = embeddingApiKeySet; }
}
