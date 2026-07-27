package com.rag.backend.rerank;

public class RerankSettings {
    private Long id;
    private String provider;
    private String baseUrl;
    private String model;
    private String apiKey;
    private Boolean failOpen;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Boolean getFailOpen() { return failOpen; }
    public void setFailOpen(Boolean failOpen) { this.failOpen = failOpen; }
}
