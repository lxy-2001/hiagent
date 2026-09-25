package com.agentflow.llm;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.model.UsageSource;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps OpenAI-compatible JSON at the adapter boundary; no provider type leaves this module. */
public final class ProviderDecisionMapper {
    private ProviderDecisionMapper() {
    }

    public static Map<String, Object> requestBody(AgentModelRequest request, String model,
                                                    ObjectMapper mapper) {
        Map<String, Object> body = new LinkedHashMap<>();
        ProviderToolNames.aliases(request);
        body.put("model", model);
        if (request.maxCompletionTokens() != null) {
            body.put("max_tokens", request.maxCompletionTokens());
        }
        body.put("messages", request.messages().stream()
                .map(message -> messageMap(message, mapper)).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream()
                    .map(definition -> toolMap(definition)).toList());
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", false);
        }
        return body;
    }

    public static ModelDecision decision(JsonNode response, ObjectMapper mapper) {
        if (response == null || !response.isObject()) {
            throw invalid("response is empty");
        }
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw invalid("response must contain exactly one choice");
        }
        JsonNode choice = choices.get(0);
        if (choice == null || !choice.isObject()) {
            throw invalid("response choice is not an object");
        }
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw invalid("response choice has no message");
        }
        JsonNode refusal = message.path("refusal");
        if (refusal.isTextual() && !refusal.asText().isBlank()) {
            throw invalid("model refused the request");
        }
        JsonNode usageNode = response.path("usage");
        TokenUsage usage = usage(usageNode);
        UsageSource usageSource = usageNode.hasNonNull("prompt_tokens")
                && usageNode.hasNonNull("completion_tokens") ? UsageSource.REPORTED : UsageSource.UNKNOWN;
        String finishReason = textOrNull(choice.path("finish_reason"));
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isMissingNode() && !toolCalls.isNull() && !toolCalls.isArray()) {
            throw invalid("tool_calls must be an array");
        }
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            if (toolCalls.size() != 1) {
                throw invalid("multiple tool calls are not supported");
            }
            if (finishReason != null && !finishReason.isBlank() && !"tool_calls".equals(finishReason)) {
                throw invalid("unexpected finish reason for tool call");
            }
            JsonNode toolCall = toolCalls.get(0);
            if (toolCall == null || !toolCall.isObject()) {
                throw invalid("tool call is not an object");
            }
            JsonNode type = toolCall.path("type");
            if (!type.isMissingNode() && (!type.isTextual() || !"function".equals(type.asText()))) {
                throw invalid("only function tool calls are supported");
            }
            String callId = textOrNull(toolCall.path("id"));
            JsonNode function = toolCall.path("function");
            if (!function.isObject()) {
                throw invalid("tool call has no function");
            }
            String name = textOrNull(function.path("name"));
            String content = textOrNull(message.path("content"));
            if (callId == null || name == null || (content != null && !content.isBlank())) {
                throw invalid("tool call is missing id/name or contains final content");
            }
            JsonNode argumentsNode = function.path("arguments");
            if (argumentsNode.isTextual()) {
                try {
                    argumentsNode = mapper.readTree(argumentsNode.asText());
                } catch (JacksonException ex) {
                    throw invalid("tool arguments are not valid JSON", ex);
                }
            }
            if (!argumentsNode.isObject()) {
                throw invalid("tool arguments must be a JSON object");
            }
            Map<String, Object> arguments = objectMap(argumentsNode);
            String responseId = textOrNull(response.path("id"));
            String decisionId = responseId == null ? "decision-" + callId : responseId;
            try {
                return new ToolCallDecision(decisionId, new ToolCall(callId, name,
                        new ToolArguments(arguments)), usage, usageSource);
            } catch (IllegalArgumentException ex) {
                throw invalid("tool arguments exceed the Core safety limits", ex);
            }
        }

        String content = textOrNull(message.path("content"));
        if (content == null || content.isBlank()) {
            throw invalid("model response contains neither a tool call nor final content");
        }
        if (finishReason != null && !finishReason.isBlank()
                && !"stop".equals(finishReason) && !"length".equals(finishReason)) {
            throw invalid("unknown finish reason");
        }
        String responseId = textOrNull(response.path("id"));
        String decisionId = responseId == null ? "decision-final" : responseId;
        return new FinalAnswerDecision(decisionId, content, usage, usageSource);
    }

    private static Map<String, Object> messageMap(ModelMessage message, ObjectMapper mapper) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", message.role());
        if (message.toolCall() != null) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", ProviderToolNames.encode(message.toolCall().name()));
            try {
                function.put("arguments", mapper.writeValueAsString(message.toolCall().arguments().values()));
            } catch (JacksonException ex) {
                throw new ModelClientException("Failed to serialize tool arguments", ex);
            }
            result.put("tool_calls", List.of(Map.of("id", message.toolCall().callId(),
                    "type", "function", "function", function)));
            result.put("content", message.content());
        } else {
            result.put("content", message.content());
            if (message.name() != null) {
                result.put("name", ProviderToolNames.encode(message.name()));
            }
            if (message.toolCallId() != null) {
                result.put("tool_call_id", message.toolCallId());
            }
        }
        return result;
    }

    private static Map<String, Object> toolMap(ToolDefinition definition) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", ProviderToolNames.encode(definition.name()));
        function.put("description", definition.description());
        function.put("parameters", schemaMap(definition.schema()));
        return Map.of("type", "function", "function", function);
    }

    private static Map<String, Object> schemaMap(ToolSchema schema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, ParameterSpec> entry : schema.properties().entrySet()) {
            ParameterSpec spec = entry.getValue();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", jsonType(spec));
            if (!spec.description().isBlank()) value.put("description", spec.description());
            if (spec.minLength() != null) value.put("minLength", spec.minLength());
            if (spec.maxLength() != null) value.put("maxLength", spec.maxLength());
            if (spec.minInteger() != null) value.put("minimum", spec.minInteger());
            if (spec.maxInteger() != null) value.put("maximum", spec.maxInteger());
            if (spec.minNumber() != null) value.put("minimum", spec.minNumber());
            if (spec.maxNumber() != null) value.put("maximum", spec.maxNumber());
            if (!spec.enumValues().isEmpty()) value.put("enum", spec.enumValues());
            if (spec.pattern() != null) value.put("pattern", spec.pattern());
            properties.put(entry.getKey(), value);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", schema.required().stream().sorted().toList());
        result.put("additionalProperties", schema.allowAdditionalProperties());
        return result;
    }

    private static String jsonType(ParameterSpec spec) {
        return switch (spec.type()) {
            case STRING -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
        };
    }

    private static TokenUsage usage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return TokenUsage.empty();
        }
        if (!node.isObject()) {
            throw invalid("usage must be an object");
        }
        int prompt = nonNegativeInt(node.path("prompt_tokens"), "prompt_tokens");
        int completion = nonNegativeInt(node.path("completion_tokens"), "completion_tokens");
        JsonNode totalNode = node.path("total_tokens");
        int total;
        if (totalNode == null || totalNode.isMissingNode() || totalNode.isNull()) {
            try {
                total = Math.addExact(prompt, completion);
            } catch (ArithmeticException ex) {
                throw invalid("usage total exceeds supported range", ex);
            }
        } else {
            total = nonNegativeInt(totalNode, "total_tokens");
        }
        return new TokenUsage(prompt, completion, total);
    }

    private static int nonNegativeInt(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        if (!node.isIntegralNumber() || node.asLong() < 0 || node.asLong() > Integer.MAX_VALUE) {
            throw invalid("usage field " + field + " is invalid");
        }
        return node.asInt();
    }

    private static Map<String, Object> objectMap(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
        return result;
    }

    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) {
            try {
                BigInteger integer = new BigInteger(node.asText());
                if (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                        || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                    throw invalid("integer argument is outside the supported range");
                }
                return integer.longValue();
            } catch (NumberFormatException ex) {
                throw invalid("integer argument is invalid", ex);
            }
        }
        if (node.isFloatingPointNumber()) {
            double number = node.asDouble();
            if (!Double.isFinite(number)) {
                throw invalid("number argument must be finite");
            }
            return number;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(value(item)));
            return values;
        }
        if (node.isObject()) return objectMap(node);
        throw invalid("unsupported JSON argument value");
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static ModelClientException invalid(String message) {
        return new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE,
                "Invalid model decision: " + message);
    }

    private static ModelClientException invalid(String message, Throwable cause) {
        return new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE,
                "Invalid model decision: " + message, cause);
    }
}
