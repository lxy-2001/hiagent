package com.agentflow.llm;

import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class ProviderToolNamesTest {
    @Test
    void mapsDefinitionsHistoryAndResponseWithoutCollisionsOrChangingCoreNames() {
        var mapper = new ObjectMapper();
        var properties = new AgentFlowProperties();
        properties.model().setApiKey("test-key");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OpenAiAgentModelClient(new OpenAiCompatibleModelClient(properties, builder));
        var names = List.of("knowledge.search", "knowledge_search", "x".repeat(100), "af_tool_reserved");
        var definitions = names.stream().map(name -> new ToolDefinition(name, "Search", RiskLevel.LOW,
                new ToolSchema(Map.of(), Set.of(), false))).toList();
        var call = new ToolCall("call-1", "knowledge.search", new ToolArguments(Map.of()));
        var request = new AgentModelRequest("task", "session", "user", "search",
                List.of(ModelMessage.user("search"), ModelMessage.assistantToolCall(call),
                        new ModelMessage("tool", "source text", call.name(), call.callId(), null)), definitions, 2);
        server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(http -> {
            var body = mapper.readTree(((MockClientHttpRequest) http).getBodyAsString());
            assertThat(body.path("parallel_tool_calls").asBoolean(true)).isFalse();
            var wireNames = new java.util.ArrayList<String>();
            for (var tool : body.path("tools")) {
                String name = tool.path("function").path("name").asText();
                assertThat(name).matches("[a-zA-Z0-9_-]{1,64}");
                wireNames.add(name);
            }
            assertThat(wireNames).doesNotHaveDuplicates();
            assertThat(wireNames.get(1)).isEqualTo("knowledge_search");
            String alias = wireNames.get(0);
            assertThat(body.path("messages").path(1).path("tool_calls").path(0).path("function").path("name").asText()).isEqualTo(alias);
            assertThat(body.path("messages").path(2).path("name").asText()).isEqualTo(alias);
            String response = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{\"id\":\"next\",\"type\":\"function\",\"function\":{\"name\":\""
                    + alias + "\",\"arguments\":\"{}\"}}]}}]}";
            var result = new MockClientHttpResponse(response.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            result.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            return result;
        });
        var decision = (ToolCallDecision) client.decide(request);
        assertThat(decision.toolCall().name()).isEqualTo("knowledge.search");
        assertThat(decision.toolCall().callId()).isEqualTo("next");
        assertThat(request.tools().get(0).name()).isEqualTo("knowledge.search");
        server.verify();
    }
}
