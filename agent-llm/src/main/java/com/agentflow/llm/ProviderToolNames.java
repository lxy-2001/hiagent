package com.agentflow.llm;

import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.tool.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Provider-only aliases; internal registry names remain unchanged. */
final class ProviderToolNames {
    private static final String PREFIX = "af_tool_";

    private ProviderToolNames() { }

    static String encode(String name) {
        if (name.matches("[a-zA-Z0-9_-]{1,64}") && !name.startsWith(PREFIX)) return name;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static Map<String, String> aliases(AgentModelRequest request) {
        Map<String, String> names = new HashMap<>();
        request.tools().forEach(tool -> {
            String previous = names.putIfAbsent(encode(tool.name()), tool.name());
            if (previous != null && !previous.equals(tool.name())) {
                throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR, "Provider tool alias collision");
            }
        });
        return names;
    }

    static ModelDecision restore(ModelDecision decision, Map<String, String> names) {
        if (!(decision instanceof ToolCallDecision tool)) return decision;
        String original = names.get(tool.toolCall().name());
        if (original == null) {
            // Unknown names still reach the runtime's normal unknown-tool validation.
            return decision;
        }
        return new ToolCallDecision(tool.decisionId(), new ToolCall(tool.toolCall().callId(),
                original, tool.toolCall().arguments()), tool.usage(), tool.usageSource());
    }
}
