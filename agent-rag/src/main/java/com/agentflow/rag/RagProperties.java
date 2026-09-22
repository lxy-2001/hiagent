package com.agentflow.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agentflow.rag")
public class RagProperties {
    private boolean enabled;
    private String storeDirectory = "";
    private String embeddingSpaceId = "";
    private boolean allowKeywordFallback;
    private int chunkSize = 800;
    private int overlap = 100;
    private final Qdrant qdrant = new Qdrant();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStoreDirectory() { return storeDirectory; }
    public void setStoreDirectory(String value) { storeDirectory = value; }
    public String getEmbeddingSpaceId() { return embeddingSpaceId; }
    public void setEmbeddingSpaceId(String value) { embeddingSpaceId = value; }
    public boolean isAllowKeywordFallback() { return allowKeywordFallback; }
    public void setAllowKeywordFallback(boolean value) { allowKeywordFallback = value; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int value) { chunkSize = value; }
    public int getOverlap() { return overlap; }
    public void setOverlap(int value) { overlap = value; }
    public Qdrant getQdrant() { return qdrant; }

    public static class Qdrant {
        private String baseUrl = "http://localhost:6333";
        private String apiKey = "";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
    }
}
