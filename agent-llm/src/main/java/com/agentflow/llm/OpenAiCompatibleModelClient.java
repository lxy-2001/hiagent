package com.agentflow.llm;

import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatCompletionResponse;
import com.agentflow.core.chat.ChatMessage;
import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import com.agentflow.core.model.ModelPrompt;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class OpenAiCompatibleModelClient implements AgentModelClient, EmbeddingClient, ChatModelClient {

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    private final AgentFlowProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleModelClient(AgentFlowProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        RestClient.Builder configured = builder.baseUrl(resolveBaseUrl());
        if (hasApiKey()) {
            configured.defaultHeader("Authorization", "Bearer " + properties.model().getApiKey());
        }
        this.restClient = configured.build();
    }

    @Override
    public String generate(ModelPrompt prompt) {
        return complete(new ChatCompletionRequest(List.of(
                ChatMessage.system(prompt.system()),
                ChatMessage.user(prompt.user())
        ), null, null, null)).content();
    }

    @Override
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        if (!hasApiKey()) {
            return fallbackAnswer(request);
        }
        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(chatBody(request, false))
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return fallbackAnswer(request);
        }
        String content = response.path("choices").path(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            return fallbackAnswer(request);
        }
        return new ChatCompletionResponse(provider(), response.path("model").asText(resolveModel(request.model())),
                content, parseUsage(response.path("usage")), false);
    }

    @Override
    public ChatCompletionResponse stream(ChatCompletionRequest request, Consumer<String> deltaConsumer) {
        if (!hasApiKey()) {
            ChatCompletionResponse fallback = fallbackAnswer(request);
            if (deltaConsumer != null) {
                deltaConsumer.accept(fallback.content());
            }
            return fallback;
        }
        StringBuilder content = new StringBuilder();
        AtomicReference<String> responseModel = new AtomicReference<>(resolveModel(request.model()));
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.empty());
        return restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(chatBody(request, true))
                .exchange((httpRequest, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("Chat completion stream failed: " + response.getStatusCode());
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String payload = line.substring("data:".length()).trim();
                            if (payload.isBlank()) {
                                continue;
                            }
                            if ("[DONE]".equals(payload)) {
                                break;
                            }
                            handleStreamPayload(payload, content, responseModel, usage, deltaConsumer);
                        }
                    } catch (IOException ex) {
                        throw new IllegalStateException("Failed to read chat completion stream", ex);
                    }
                    return new ChatCompletionResponse(provider(), responseModel.get(), content.toString(), usage.get(), false);
                });
    }

    @Override
    public List<Double> embed(String text) {
        if (!hasApiKey()) {
            return deterministicEmbedding(text, properties.model().getEmbeddingDimensions());
        }
        try {
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
        } catch (RuntimeException ex) {
            return deterministicEmbedding(text, properties.model().getEmbeddingDimensions());
        }
    }

    private Map<String, Object> chatBody(ChatCompletionRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveModel(request.model()));
        body.put("messages", request.messages().stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private void handleStreamPayload(String payload, StringBuilder content, AtomicReference<String> responseModel,
                                     AtomicReference<TokenUsage> usage, Consumer<String> deltaConsumer) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String model = node.path("model").asText();
            if (model != null && !model.isBlank()) {
                responseModel.set(model);
            }
            JsonNode usageNode = node.path("usage");
            if (usageNode.isObject()) {
                usage.set(parseUsage(usageNode));
            }
            JsonNode deltaNode = node.path("choices").path(0).path("delta").get("content");
            if (deltaNode == null || deltaNode.isNull()) {
                return;
            }
            String delta = deltaNode.asText();
            if (delta == null || delta.isEmpty()) {
                return;
            }
            content.append(delta);
            if (deltaConsumer != null) {
                deltaConsumer.accept(delta);
            }
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to parse chat completion stream payload", ex);
        }
    }

    private TokenUsage parseUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.empty();
        }
        return new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0)
        );
    }

    private boolean hasApiKey() {
        String apiKey = properties.model().getApiKey();
        return apiKey != null && !apiKey.isBlank() && !"change-me".equals(apiKey);
    }

    private ChatCompletionResponse fallbackAnswer(ChatCompletionRequest request) {
        String lastUserMessage = request.messages().stream()
                .filter(message -> "user".equals(message.role()))
                .reduce((left, right) -> right)
                .map(ChatMessage::content)
                .orElse("");
        String content = """
                当前未配置真实 LLM API Key，已使用本地兜底回答。

                你已经打通了 OpenAI-compatible 对话接口骨架。配置 DeepSeek 或 OpenAI 的 API Key 后，
                系统会通过 /chat/completions 发起真实模型调用，并支持多轮上下文和 SSE 流式输出。

                用户消息：
                %s
                """.formatted(lastUserMessage);
        return new ChatCompletionResponse(provider(), resolveModel(request.model()), content, TokenUsage.empty(), true);
    }

    private String provider() {
        String provider = properties.model().getProvider();
        return provider == null || provider.isBlank() ? "deepseek" : provider;
    }

    private String resolveModel(String requestModel) {
        if (requestModel != null && !requestModel.isBlank()) {
            return requestModel;
        }
        String configured = properties.model().getChatModel();
        return configured == null || configured.isBlank() ? "deepseek-v4-pro" : configured;
    }

    private String resolveBaseUrl() {
        String configured = properties.model().getBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured);
        }
        return switch (provider()) {
            case "openai" -> OPENAI_BASE_URL;
            case "deepseek" -> DEEPSEEK_BASE_URL;
            default -> DEEPSEEK_BASE_URL;
        };
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
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
