package com.agentflow.llm;

import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatCompletionResponse;
import com.agentflow.core.chat.ChatMessage;
import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared OpenAI-compatible HTTP/JSON transport.
 *
 * This class deliberately does not implement a core model port. The three
 * adapter beans are separate so an application can replace one capability
 * without changing the other two.
 */
public class OpenAiCompatibleModelClient {

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

    tools.jackson.databind.ObjectMapper objectMapper() {
        return objectMapper;
    }

    public JsonNode completeAgent(AgentModelRequest request) {
        requireApiKey("agent decision");
        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ProviderDecisionMapper.requestBody(request, resolveModel(null), objectMapper))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException ex) {
            throw providerFailure("Agent decision provider request failed", ex);
        }
    }

    public String generate(ModelPrompt prompt) {
        return complete(new ChatCompletionRequest(List.of(
                ChatMessage.system(prompt.system()),
                ChatMessage.user(prompt.user())
        ), null, null, null)).content();
    }

    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        requireApiKey("chat completion");
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chatBody(request, false))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException ex) {
            throw providerFailure("Chat completion provider request failed", ex);
        }
        if (response == null || response.isNull() || response.isMissingNode()) {
            throw new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE, "Chat completion returned an empty response");
        }
        JsonNode contentNode = response.path("choices").path(0).path("message").path("content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            throw new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE, "Chat completion returned empty content");
        }
        return new ChatCompletionResponse(provider(),
                response.path("model").asText(resolveModel(request.model())),
                contentNode.asText(), parseUsage(response.path("usage")), false);
    }

    public ChatCompletionResponse stream(ChatCompletionRequest request, Consumer<String> deltaConsumer) {
        requireApiKey("chat completion stream");
        try {
            ChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(chatBody(request, true))
                    .exchange((httpRequest, clientResponse) -> {
                        if (clientResponse.getStatusCode().isError()) {
                            throw new ModelClientException(
                                    "Chat completion stream provider request failed: "
                                            + clientResponse.getStatusCode());
                        }
                        StringBuilder content = new StringBuilder();
                        AtomicReference<String> responseModel =
                                new AtomicReference<>(resolveModel(request.model()));
                        AtomicReference<TokenUsage> usage =
                                new AtomicReference<>(TokenUsage.empty());
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                clientResponse.getBody(), StandardCharsets.UTF_8))) {
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
                            throw new ModelClientException(
                                    "Failed to read chat completion stream", ex);
                        }
                        if (content.isEmpty()) {
                            throw new ModelClientException(
                                    "Chat completion stream returned empty content");
                        }
                        return new ChatCompletionResponse(provider(), responseModel.get(),
                                content.toString(), usage.get(), false);
                    });
            if (response == null) {
                throw new ModelClientException("Chat completion stream returned an empty response");
            }
            return response;
        } catch (ModelClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw providerFailure("Chat completion stream provider request failed", ex);
        }
    }

    public List<Double> embed(String text) {
        requireApiKey("embedding");
        JsonNode response;
        try {
            Map<String, Object> body = Map.of(
                    "model", properties.model().getEmbeddingModel(),
                    "input", text,
                    "dimensions", properties.model().getEmbeddingDimensions()
            );
            response = restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException ex) {
            throw providerFailure("Embedding provider request failed", ex);
        }
        JsonNode embedding = response == null ? null : response.path("data").path(0).path("embedding");
        if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE, "Embedding provider returned an empty or invalid response");
        }
        List<Double> vector = new ArrayList<>(embedding.size());
        for (JsonNode value : embedding) {
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                throw new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE, "Embedding provider returned an invalid vector");
            }
            vector.add(value.asDouble());
        }
        return vector;
    }

    private void requireApiKey(String operation) {
        if (!hasApiKey()) {
            throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,
                    "LLM API key is required before invoking " + operation);
        }
    }

    private ModelClientException providerFailure(String message, RuntimeException cause) {
        return new ModelClientException(ModelClientException.PROVIDER_ERROR,
                message + ": " + safeMessage(cause), cause);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        String apiKey = properties.model().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            message = message.replace(apiKey, "[redacted]");
        }
        return ModelClientException.sanitize(message);
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

    private void handleStreamPayload(String payload, StringBuilder content,
                                     AtomicReference<String> responseModel,
                                     AtomicReference<TokenUsage> usage,
                                     Consumer<String> deltaConsumer) {
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
            throw new ModelClientException("Failed to parse chat completion stream payload", ex);
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
        return apiKey != null && !apiKey.isBlank();
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

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
