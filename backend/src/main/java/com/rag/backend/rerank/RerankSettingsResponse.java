package com.rag.backend.rerank;

public class RerankSettingsResponse {
    private String provider;
    private String baseUrl;
    private String model;
    private boolean apiKeySet;
    private boolean failOpen;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isApiKeySet() { return apiKeySet; }
    public void setApiKeySet(boolean apiKeySet) { this.apiKeySet = apiKeySet; }
    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
}
