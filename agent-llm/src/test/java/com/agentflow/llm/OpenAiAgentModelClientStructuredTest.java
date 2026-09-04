package com.agentflow.llm;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.core.tool.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiAgentModelClientStructuredTest {
    @Test
    void mapsNativeToolCallAndStructuredSchemaToCoreDecision() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                new OpenAiCompatibleModelClient(properties(), builder));
        ToolDefinition tool = new ToolDefinition("uppercase-text", "uppercase", RiskLevel.LOW,
                new ToolSchema(Map.of("text", ParameterSpec.requiredString(64)), Set.of("text"), false));
        AgentModelRequest request = new AgentModelRequest("task", "session", "user", "hello",
                List.of(ModelMessage.user("hello")), List.of(tool), 1, 17);

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "model":"deepseek-v4-pro",
                          "max_tokens":17,
                          "messages":[{"role":"user","content":"hello"}],
                          "tools":[{"type":"function","function":{"name":"uppercase-text","description":"uppercase","parameters":{"type":"object","required":["text"],"additionalProperties":false}}}],
                          "tool_choice":"auto"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"id":"decision-1","model":"deepseek-v4-pro","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"uppercase-text","arguments":"{\\\"text\\\":\\\"hello\\\"}"}}]}}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                        """, MediaType.APPLICATION_JSON));

        var decision = client.decide(request);

        assertThat(decision).isInstanceOf(ToolCallDecision.class);
        var toolDecision = (ToolCallDecision) decision;
        assertThat(toolDecision.decisionId()).isEqualTo("decision-1");
        assertThat(toolDecision.toolCall().callId()).isEqualTo("call-1");
        assertThat(toolDecision.toolCall().arguments().values().get("text")).isEqualTo("hello");
        assertThat(toolDecision.usage()).isEqualTo(new TokenUsage(3, 2, 5));
        server.verify();
    }

    @Test
    void mapsFinalAnswerAndConfirmedToolObservationMessages() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiAgentModelClient client = new OpenAiAgentModelClient(
                new OpenAiCompatibleModelClient(properties(), builder));
        ToolCall call = new ToolCall("call-1", "uppercase-text",
                new ToolArguments(Map.of("text", "hello")));
        AgentModelRequest request = new AgentModelRequest("task", "session", "user", "hello",
                List.of(ModelMessage.user("hello"), ModelMessage.assistantToolCall(call),
                        ModelMessage.toolResult(new com.agentflow.core.tool.ToolResult("uppercase-text", "HELLO",
                                com.agentflow.core.tool.ToolResultStatus.SUCCESS, null, null, false, "call-1"))),
                List.of(), 2);
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(content().json("""
                        {
                          "messages":[
                            {"role":"user","content":"hello"},
                            {"role":"assistant","content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"uppercase-text","arguments":"{\\\"text\\\":\\\"hello\\\"}"}}]},
                            {"role":"tool","content":"HELLO","name":"uppercase-text","tool_call_id":"call-1"}
                          ]
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"final-1\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"HELLO\"}}]}", MediaType.APPLICATION_JSON));

        var decision = client.decide(request);

        assertThat(decision).isEqualTo(new FinalAnswerDecision("final-1", "HELLO", TokenUsage.empty()));
        server.verify();
    }

    private AgentFlowProperties properties() {
        AgentFlowProperties properties = new AgentFlowProperties();
        properties.model().setProvider("deepseek");
        properties.model().setApiKey("test-api-key");
        properties.model().setChatModel("deepseek-v4-pro");
        return properties;
    }
}
