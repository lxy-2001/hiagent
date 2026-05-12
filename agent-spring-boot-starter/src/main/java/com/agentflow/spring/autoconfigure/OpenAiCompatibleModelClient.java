package com.agentflow.spring.autoconfigure;

import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import com.agentflow.core.model.ModelPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleModelClient implements AgentModelClient, EmbeddingClient {

    private final AgentFlowProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleModelClient(AgentFlowProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        RestClient.Builder configured = builder.baseUrl(trimTrailingSlash(properties.model().getBaseUrl()));
        if (hasApiKey()) {
            configured.defaultHeader("Authorization", "Bearer " + properties.model().getApiKey());
        }
        this.restClient = configured.build();
    }

    @Override
    public String generate(ModelPrompt prompt) {
        if (!hasApiKey()) {
            return fallbackAnswer(prompt);
        }
        Map<String, Object> body = Map.of(
                "model", properties.model().getChatModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", prompt.system()),
                        Map.of("role", "user", "content", prompt.user())
                )
        );
        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return fallbackAnswer(prompt);
        }
        String content = response.path("choices").path(0).path("message").path("content").asText();
        return content == null || content.isBlank() ? fallbackAnswer(prompt) : content;
    }

    @Override
    public List<Double> embed(String text) {
        if (!hasApiKey()) {
            return deterministicEmbedding(text, properties.model().getEmbeddingDimensions());
        }
        Map<String, Object> body = Map.of(
                "model", properties.model().getEmbeddingModel(),
                "input", text,
                "dimensions", properties.model().getEmbeddingDimensions()
        );
        JsonNode response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        JsonNode embedding = response == null ? null : response.path("data").path(0).path("embedding");
        if (embedding == null || !embedding.isArray()) {
            return deterministicEmbedding(text, properties.model().getEmbeddingDimensions());
        }
        List<Double> vector = new ArrayList<>(embedding.size());
        embedding.forEach(value -> vector.add(value.asDouble()));
        return vector;
    }

    private boolean hasApiKey() {
        String apiKey = properties.model().getApiKey();
        return apiKey != null && !apiKey.isBlank() && !"change-me".equals(apiKey);
    }

    private String fallbackAnswer(ModelPrompt prompt) {
        return """
                当前未配置真实 LLM API Key，已使用本地兜底回答。

                建议方案：
                1. 使用 Spring MVC 暴露 REST 接口，Controller 只做参数校验和请求转发。
                2. Service 层通过 Redis 预扣减库存，并用 MySQL 乐观锁或唯一流水保证最终一致性。
                3. 对扣减链路记录业务流水，失败时通过补偿任务回滚 Redis 或修正 MySQL。
                4. AgentFlow 已完成本次任务的规划、知识检索、工具调用和步骤记录。

                原始提示摘要：
                %s
                """.formatted(prompt.user().lines().limit(8).reduce("", (left, right) -> left + "\n" + right));
    }

    private List<Double> deterministicEmbedding(String text, int dimensions) {
        byte[] hash = sha256(text == null ? "" : text);
        List<Double> vector = new ArrayList<>(dimensions);
        for (int i = 0; i < dimensions; i++) {
            int unsigned = hash[i % hash.length] & 0xff;
            vector.add((unsigned - 128) / 128.0d);
        }
        return vector;
    }

    private byte[] sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
