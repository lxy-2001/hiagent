package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.tool.*;
import com.agentflow.web.run.RunControl;
import java.time.Instant;
import java.util.*;

final class ApprovalFixtures {
    static final String RUN = "42c6ac62-d44b-40da-9e87-419048ec4767";
    static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    static ApprovalRequest request() {
        AgentTool tool = new AgentTool() {
            public ToolDefinition definition() { return new ToolDefinition("local", "test", RiskLevel.HIGH, new ToolSchema(Map.of())); }
            public ToolResult execute(ToolArguments args, ToolContext context) { throw new AssertionError("API must never execute"); }
        };
        var call = PreparedToolCall.prepare(RUN, "session", "owner", new ToolRegistration(tool, true),
                new ToolCall("call", "local", new ToolArguments(Map.of())), NOW);
        return new ApprovalRequest(UUID.randomUUID(), call, new ToolPolicyDecision(ToolPolicyDecision.Action.REQUIRE_APPROVAL,
                RiskLevel.HIGH, ToolPolicyDecision.Effect.WRITE, "Append note", Set.of()), NOW, NOW.plusSeconds(30));
    }
    static RunControl control() {
        var control = RunControl.queued(RUN, "owner", 0, Long.MAX_VALUE);
        control.claimStart(1); control.claimRuntimeCall(true);
        return control;
    }
}
