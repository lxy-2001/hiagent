package com.agentflow.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agentflow")
public class AgentFlowProperties {

    private final Model model = new Model();
    private final Tools tools = new Tools();
    private final Security security = new Security();
    private final Mcp mcp = new Mcp();

    public Model model() {
        return model;
    }

    public Model getModel() {
        return model;
    }

    public Tools tools() {
        return tools;
    }

    public Tools getTools() {
        return tools;
    }

    public Security security() {
        return security;
    }

    public Security getSecurity() {
        return security;
    }

    public Mcp mcp() {
        return mcp;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public static class Model {
        private String provider = "deepseek";
        private String baseUrl = "";
        private String apiKey = "";
        private String chatModel = "deepseek-v4-pro";
        private String embeddingModel = "text-embedding-v4";
        private String embeddingBaseUrl = "";
        private String embeddingApiKey = "";
        private int embeddingDimensions = 2048;
        private Duration timeout = Duration.ofSeconds(60);

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
        public void setEmbeddingBaseUrl(String value) { embeddingBaseUrl = value; }
        public String getEmbeddingApiKey() { return embeddingApiKey; }
        public void setEmbeddingApiKey(String value) { embeddingApiKey = value; }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Tools {
        private int maxSteps = 6;

        public int maxSteps() {
            return maxSteps;
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }
    }

    public static class Security {
        private final Jwt jwt = new Jwt();
        private Duration refreshTokenTtl = Duration.ofDays(7);

        public Jwt jwt() {
            return jwt;
        }

        public Jwt getJwt() {
            return jwt;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public static class Jwt {
            private String secret = "";
            private Duration accessTokenTtl = Duration.ofMinutes(30);

            public String getSecret() {
                return secret;
            }

            public void setSecret(String secret) {
                this.secret = secret;
            }

            public Duration getAccessTokenTtl() {
                return accessTokenTtl;
            }

            public void setAccessTokenTtl(Duration accessTokenTtl) {
                this.accessTokenTtl = accessTokenTtl;
            }
        }
    }

    public static class Mcp {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
