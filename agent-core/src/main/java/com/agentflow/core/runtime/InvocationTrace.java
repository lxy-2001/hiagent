package com.agentflow.core.runtime;

import com.agentflow.core.approval.*;
import com.agentflow.core.tool.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Run-local mutable measurements; exported only as immutable parameter-free facts. */
final class InvocationTrace {
    final PreparedToolCall prepared;
    final ToolPolicyDecision policy;
    ApprovalRequest approval;
    ApprovalResolution resolution;
    Instant dispatchAt;
    Long executionMillis;
    ToolInvocationRecord.Outcome outcome = ToolInvocationRecord.Outcome.NOT_DISPATCHED;
    String errorCode;

    InvocationTrace(PreparedToolCall prepared, ToolPolicyDecision policy) {
        this.prepared=prepared; this.policy=policy;
    }
    void dispatched() {
        dispatchAt=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        outcome=ToolInvocationRecord.Outcome.UNKNOWN;
    }
    void result(ToolResult result,long millis) {
        executionMillis=millis;
        errorCode=result.errorCode();
        outcome=result.status()==ToolResultStatus.SUCCESS ? ToolInvocationRecord.Outcome.SUCCEEDED
                : uncertain(result.errorCode()) ? ToolInvocationRecord.Outcome.UNKNOWN : ToolInvocationRecord.Outcome.FAILED;
    }
    private boolean uncertain(String code) {
        return java.util.Set.of("MCP_UNAVAILABLE", "MCP_TIMEOUT", "MCP_PROTOCOL_ERROR", "CANCELLED", "TIMED_OUT").contains(code)
                || policy.effect()==ToolPolicyDecision.Effect.WRITE && !"MCP_TOOL_ERROR".equals(code);
    }
    ToolInvocationRecord snapshot() {
        String name=prepared.call().name();
        String[] remote=name.startsWith("mcp.") ? name.split("\\.",3) : new String[0];
        return new ToolInvocationRecord("006-v1",prepared.runId(),prepared.callId(),name,
                remote.length==3?remote[1]:null,remote.length==3?remote[2]:null,
                prepared.toolDefinitionVersion(),policy.policyVersion(),prepared.argumentsDigest(),policy.risk(),policy.effect(),
                policy.action(),prepared.createdAt(),dispatchAt,approval==null?null:approval.approvalId(),
                resolution!=null?resolution.status():approval==null?null:ApprovalStatus.PENDING,
                resolution==null?null:resolution.waitMillis(),dispatchAt==null?0:1,executionMillis,outcome,errorCode);
    }
}
