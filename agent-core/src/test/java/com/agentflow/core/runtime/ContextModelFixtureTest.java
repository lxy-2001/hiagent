package com.agentflow.core.runtime;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.support.ContextModelFixture;
import com.agentflow.core.tool.DefaultToolExecutor;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextModelFixtureTest {
    private static final FinalAnswerDecision FINAL =
            new FinalAnswerDecision("final", "done", new TokenUsage(3, 2, 5));

    @Test
    void replaysCopiedScriptAndKeepsDetachedImmutableRequestSnapshots() {
        var call = new ToolCall("call-1", "echo", new ToolArguments(Map.of("text", List.of("original"))));
        ModelDecision first = new ToolCallDecision("tool", call, TokenUsage.empty());
        var script = new ArrayList<>(List.of(first, FINAL));
        var model = new ContextModelFixture(script);
        script.clear();
        var messages = new ArrayList<>(List.of(ModelMessage.user("hello"), ModelMessage.assistantToolCall(call)));
        var request = request(1, messages);

        assertEquals(first, model.decide(request));
        var snapshot = model.requests();
        messages.clear();
        assertEquals(FINAL, model.decide(request(2, List.of(ModelMessage.user("next")))));

        assertEquals(2, model.callCount());
        assertEquals(List.of(request), snapshot);
        assertEquals(2, snapshot.get(0).messages().size());
        assertEquals(128, snapshot.get(0).maxCompletionTokens());
        assertEquals(List.of(1, 2), model.requests().stream().map(AgentModelRequest::iteration).toList());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get(0).messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().values().clear());
    }

    @Test
    void recordsUnexpectedCallsAndFailsExplicitlyWhenScriptIsExhausted() {
        var model = new ContextModelFixture(List.of(FINAL));
        model.decide(request(1, List.of(ModelMessage.user("hello"))));
        var extra = request(2, List.of(ModelMessage.user("unexpected")));

        assertTrue(assertThrows(AssertionError.class, () -> model.decide(extra)).getMessage().contains("exhausted"));
        assertEquals(2, model.callCount());
        assertEquals(extra, model.requests().get(1));
    }

    @Test
    void rejectsNullRequestsWithoutConsumingTheScript() {
        var model = new ContextModelFixture(List.of(FINAL));
        assertThrows(NullPointerException.class, () -> model.decide(null));
        assertEquals(0, model.callCount());
        assertEquals(FINAL, model.decide(request(1, List.of(ModelMessage.user("hello")))));
    }

    @Test
    void drivesRealRuntimeToolThenFinalWithoutAProviderOrNetwork() {
        var tool = RuntimeTestSupport.tool("echo", (arguments, context) -> ToolResult.success("echo", "observation"));
        var registry = RuntimeTestSupport.registry(tool);
        var call = new ToolCall("call-1", "echo", new ToolArguments(Map.of()));
        var model = new ContextModelFixture(List.of(new ToolCallDecision("tool", call, TokenUsage.empty()), FINAL));
        var runtime = new DefaultAgentRuntime(model, registry, new DefaultToolExecutor(registry), null);

        var result = runtime.run(new AgentRequest("run-1", "session-1", "user-1", "hello"), AgentEventSink.NOOP);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals("done", result.finalAnswer());
        assertEquals(2, model.callCount());
        var messages = model.requests().get(1).messages();
        assertEquals(List.of("user", "assistant", "tool"), messages.stream().map(ModelMessage::role).toList());
        assertEquals(call, messages.get(1).toolCall());
        assertEquals("call-1", messages.get(2).toolCallId());
        assertEquals("observation", messages.get(2).content());
    }

    private static AgentModelRequest request(int iteration, List<ModelMessage> messages) {
        return new AgentModelRequest("run-1", "session-1", "user-1", "hello", messages, List.of(), iteration, 128);
    }
}
