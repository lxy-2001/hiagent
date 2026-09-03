package com.agentflow.core.model;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDecisionContractTest {

    @Test
    void finalDecisionRequiresNonBlankAnswer() {
        assertThrows(IllegalArgumentException.class,
                () -> new FinalAnswerDecision("d-1", "  ", TokenUsage.empty()));
    }

    @Test
    void toolDecisionCarriesStructuredImmutableCallAndUsage() {
        ToolArguments arguments = new ToolArguments(Map.of("text", "hello"));
        ToolCall call = new ToolCall("call-1", "uppercase-text", arguments);
        ToolCallDecision decision = new ToolCallDecision("d-1", call, new TokenUsage(3, 2, 5));

        assertEquals("call-1", decision.toolCall().callId());
        assertEquals("hello", decision.toolCall().arguments().values().get("text"));
        assertEquals(5, decision.usage().totalTokens());
        assertThrows(UnsupportedOperationException.class,
                () -> decision.toolCall().arguments().values().put("x", "y"));
    }

    @Test
    void modelRequestCopiesOrderedMessagesAndDefinitions() {
        ToolDefinition definition = new ToolDefinition(
                "uppercase-text", "Uppercase text", RiskLevel.LOW,
                new ToolSchema(Map.of("text", ParameterSpec.requiredString(64)),
                        java.util.Set.of("text"), false));
        List<ModelMessage> messages = List.of(ModelMessage.user("hello"));
        AgentModelRequest request = new AgentModelRequest(
                "task-1", "session-1", "user-1", "hello", messages, List.of(definition), 1);

        assertEquals("hello", request.messages().get(0).content());
        assertEquals("uppercase-text", request.tools().get(0).name());
        assertEquals(1, request.iteration());
        assertTrue(request.messages() != messages);
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(ModelMessage.user("mutate")));
    }
}
