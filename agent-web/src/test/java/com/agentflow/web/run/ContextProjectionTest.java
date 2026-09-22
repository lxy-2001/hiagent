package com.agentflow.web.run;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.runtime.DefaultAgentRuntime;
import com.agentflow.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContextProjectionTest {
    @Test
    void preservesContextBudgetOutcomeAndItsTerminalStepDespiteObserverFailure() {
        var registry = mock(ToolRegistry.class);
        when(registry.enabledDefinitions()).thenReturn(List.of());
        var events = new ArrayList<com.agentflow.core.AgentEvent>();
        var runtime = new DefaultAgentRuntime(request -> { throw new AssertionError("model must not run"); }, registry,
                step -> { throw new IllegalStateException("recorder unavailable"); });
        var result = runtime.run(new AgentRequest("r", "s", "u", "中".repeat(6000)), event -> {
            events.add(event);
            throw new IllegalStateException("subscriber disconnected");
        });
        var projection = new RunResultProjector().project("r", result, Instant.now(), false, true);
        assertThat(projection.status()).isEqualTo(RunLifecycleStatus.BUDGET_EXCEEDED);
        assertThat(projection.terminationReason().name()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
        assertThat(projection.recordingComplete()).isTrue();
        var step = projection.steps().get(0);
        assertThat(step.stepType()).isEqualTo("CONTEXT_ASSEMBLY");
        assertThat(step.errorMessage()).hasSizeLessThanOrEqualTo(1024).doesNotContain("中");
        assertThat(RunEvent.Type.values()).hasSize(4);
        var event = events.stream().filter(e -> e.type() == AgentStepType.CONTEXT_ASSEMBLY).findFirst().orElseThrow();
        assertThat(new RunEventProjector().project("r", event, Instant.now()).event().orElseThrow().type()).isEqualTo(RunEvent.Type.AGENT_STEP);
    }
}
