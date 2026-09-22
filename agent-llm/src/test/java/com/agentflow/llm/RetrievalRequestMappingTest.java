package com.agentflow.llm;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RetrievalRequestMappingTest {
    @Test
    void mapsCompleteLongToolBodyExactlyOnceWithoutPromotingItsRole() {
        String body = "source [S1]\n" + "原文".repeat(2000);
        var call = new ToolCall("c", "knowledge.search", new ToolArguments(Map.of()));
        var request = new AgentModelRequest(new AgentRequest("t", "s", "u", "question"), List.of(ModelMessage.user("question"),
                ModelMessage.assistantToolCall(call), new ModelMessage("tool", body, "knowledge.search", "c", null)), List.of(), 2, 100);
        var mapper = new JsonMapper();
        var json = mapper.valueToTree(ProviderDecisionMapper.requestBody(request, "test", mapper));
        assertThat(json.path("messages").size()).isEqualTo(3);
        assertThat(json.path("messages").get(2).path("role").asString()).isEqualTo("tool");
        assertThat(json.path("messages").get(2).path("content").asString()).isEqualTo(body);
        assertThat(json.path("messages").get(2).path("tool_call_id").asString()).isEqualTo("c");
    }
}
