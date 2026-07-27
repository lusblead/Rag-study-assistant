package com.rag.backend.rerank;

public class RerankSettingsRequest {
    private String provider;
    private String baseUrl;
    private String model;
    private String apiKey;
    private Boolean clearApiKey;
    private Boolean failOpen;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Boolean getClearApiKey() { return clearApiKey; }
    public void setClearApiKey(Boolean clearApiKey) { this.clearApiKey = clearApiKey; }
    public Boolean getFailOpen() { return failOpen; }
    public void setFailOpen(Boolean failOpen) { this.failOpen = failOpen; }
}
