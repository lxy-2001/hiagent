package com.agentflow.mcp;

import com.agentflow.core.approval.*;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.runtime.*;
import com.agentflow.core.tool.*;
import com.agentflow.mcp.fixture.LoopbackMcpServer;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class McpOutcomeIntegrationTest {
    private static final ToolExecutionPolicy POLICY = ToolExecutionPolicy.rules(Map.of("mcp.demo.project_info",
            new ToolPolicyDecision(ToolPolicyDecision.Action.REQUIRE_APPROVAL, RiskLevel.HIGH,
                    ToolPolicyDecision.Effect.WRITE, "Fixture write", Set.of())));
    private static final ApprovalGate GATE = new ApprovalGate() {
        public ApprovalResolution await(ApprovalRequest request, ToolExecutionControl control) {
            return new ApprovalResolution(request.approvalId(), ApprovalStatus.APPROVED, Instant.now(), ApprovalResolution.DecisionSource.USER, 0);
        }
        public boolean claimDispatch(ApprovalRequest request, ToolExecutionControl control) { return true; }
    };
    @Test void cancelledAfterServerReceivesWriteRetainsUnknownAndOneDispatch() throws Exception {
        var cancel = new AtomicBoolean(); var pool = Executors.newSingleThreadExecutor();
        try (var server = LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().callDelay(Duration.ofSeconds(2)));
             var client = SdkMcpClientOperationsTest.client(server)) {
            var future = pool.submit(() -> new McpRuntimeIntegrationTest().run(server, client, POLICY, "unused",
                    new AgentRunOptions(ExecutionBudget.defaults(), cancel::get, GATE)));
            assertTrue(server.awaitCall(Duration.ofSeconds(5))); cancel.set(true);
            var result = future.get(3, TimeUnit.SECONDS);
            assertEquals(TerminationReason.CANCELLED, result.terminationReason());
            assertEquals(ToolInvocationRecord.Outcome.UNKNOWN, result.toolInvocations().get(0).outcome());
            assertEquals(1, server.callCount()); assertEquals(1, result.toolInvocations().get(0).dispatchCount());
        } finally { pool.shutdownNow(); }
    }
}
