package com.agentflow.llm;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.context.*;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PerDecisionRequestMappingTest {
    @Test
    void mapsAssembledMessagesOnceAndPreservesCallCorrelationAndOutputReserve() {
        var seed = new ContextSeed(1, List.of(new ConversationTurn("old", 1, "question", "answer")), List.of(), false, 0);
        var call = new ToolCall("c", "echo", new ToolArguments(Map.of("text", "hello")));
        var chain = List.of(ModelMessage.user("now"), ModelMessage.assistantToolCall(call), new ModelMessage("tool", "observed", "echo", "c", null));
        var assembly = new ContextAssembler(ContextPolicy.defaults(), new Utf8TokenEstimator(), new ContextTextPolicy())
                .assemble(new AgentRequest("r", "s", "u", "now", seed), chain, List.of(), 2, 123);
        assertTrue(assembly.ready());
        var mapper = new ObjectMapper();
        var body = mapper.valueToTree(ProviderDecisionMapper.requestBody(assembly.request(), "test-model", mapper));
        assertEquals(123, body.path("max_tokens").asInt());
        var messages = body.path("messages");
        assertEquals(6, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("question", messages.get(1).path("content").asText());
        assertEquals("answer", messages.get(2).path("content").asText());
        assertEquals("now", messages.get(3).path("content").asText());
        assertEquals("c", messages.get(4).path("tool_calls").get(0).path("id").asText());
        assertEquals("c", messages.get(5).path("tool_call_id").asText());
        assertEquals("observed", messages.get(5).path("content").asText());
    }
}
