package com.agentflow.core.model;

import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolResult;

import java.util.Objects;

/** A confirmed, provider-neutral message in an agent decision context. */
public record ModelMessage(
        String role,
        String content,
        String name,
        String toolCallId,
        ToolCall toolCall,
        String adapterStateRef
) {
    public ModelMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        role = role.strip().toLowerCase();
        if ("tool".equals(role) && "knowledge.search".equals(name)
                && ToolResult.RETRIEVAL_PENDING.equals(content)) {
            throw new IllegalArgumentException("retrieval evidence must be bound before model delivery");
        }
        if (adapterStateRef != null && (adapterStateRef.isBlank() || adapterStateRef.length() > 128
                || !"assistant".equals(role))) {
            throw new IllegalArgumentException("invalid opaque adapter reference");
        }
        if (role.isEmpty()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (toolCallId != null && toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolCall != null && toolCallId != null
                && !toolCall.callId().equals(toolCallId)) {
            throw new IllegalArgumentException("toolCallId must match toolCall.callId");
        }
    }

    public ModelMessage(String role, String content, String name, String toolCallId, ToolCall toolCall) {
        this(role, content, name, toolCallId, toolCall, null);
    }

    public ModelMessage(String role, String content) {
        this(role, content, null, null, null, null);
    }

    /** The core carries only an opaque adapter key, never a provider payload. */
    public ModelMessage withAdapterStateRef(String reference) {
        return new ModelMessage(role, content, name, toolCallId, toolCall, reference);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage("user", content);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage("assistant", content);
    }

    public static ModelMessage assistantToolCall(ToolCall call) {
        Objects.requireNonNull(call, "call must not be null");
        return new ModelMessage("assistant", "", call.name(), call.callId(), call);
    }

    public static ModelMessage toolResult(ToolResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new ModelMessage("tool", result.outputOrEmpty(), result.toolName(), result.callId(), null);
    }
}
