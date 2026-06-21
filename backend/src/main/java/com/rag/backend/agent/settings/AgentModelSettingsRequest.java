package com.rag.backend.agent.settings;

public class AgentModelSettingsRequest {
    private String llmProvider;
    private String llmBaseUrl;
    private String llmModel;
    private String llmApiKey;
    private Boolean clearLlmApiKey;
    private String embeddingProvider;
    private String embeddingBaseUrl;
    private String embeddingModel;
    private String embeddingApiKey;
    private Boolean clearEmbeddingApiKey;

    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }

    public String getLlmBaseUrl() { return llmBaseUrl; }
    public void setLlmBaseUrl(String llmBaseUrl) { this.llmBaseUrl = llmBaseUrl; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public String getLlmApiKey() { return llmApiKey; }
    public void setLlmApiKey(String llmApiKey) { this.llmApiKey = llmApiKey; }

    public Boolean getClearLlmApiKey() { return clearLlmApiKey; }
    public void setClearLlmApiKey(Boolean clearLlmApiKey) { this.clearLlmApiKey = clearLlmApiKey; }

    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }

    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }

    public Boolean getClearEmbeddingApiKey() { return clearEmbeddingApiKey; }
    public void setClearEmbeddingApiKey(Boolean clearEmbeddingApiKey) { this.clearEmbeddingApiKey = clearEmbeddingApiKey; }
}
