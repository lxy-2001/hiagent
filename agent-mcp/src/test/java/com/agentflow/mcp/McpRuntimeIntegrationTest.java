package com.agentflow.mcp;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.runtime.*;
import com.agentflow.core.tool.*;
import com.agentflow.tool.InMemoryToolRegistry;
import com.agentflow.mcp.fixture.LoopbackMcpServer;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpRuntimeIntegrationTest {
    @Test void realReadOnlyCallReturnsToModelAndDoesNotForgeCitations() {
        for(String answer:List.of("complete","forged [S1]")) {
            try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json());var client=SdkMcpClientOperationsTest.client(server)) {
                var result=run(server,client,McpToolProviderTest.POLICY,answer,AgentRunOptions.defaults());
                assertEquals(answer.equals("complete")?RunStatus.SUCCEEDED:RunStatus.FAILED,result.status());
                assertEquals(1,server.callCount());assertEquals(1,result.toolInvocations().size());
                assertEquals("demo",result.toolInvocations().get(0).serverId());assertTrue(result.citations().isEmpty());
                assertEquals(ToolInvocationRecord.Outcome.SUCCEEDED,result.toolInvocations().get(0).outcome());
            }
        }
    }
    @Test void highRiskWithoutGateHasZeroExternalCalls() {
        var policy=ToolExecutionPolicy.rules(Map.of("mcp.demo.project_info",new ToolPolicyDecision(ToolPolicyDecision.Action.REQUIRE_APPROVAL,
                RiskLevel.HIGH,ToolPolicyDecision.Effect.WRITE,"Append",Set.of())));
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json());var client=SdkMcpClientOperationsTest.client(server)) {
            var result=run(server,client,policy,"done",AgentRunOptions.defaults());
            assertEquals(TerminationReason.APPROVAL_UNAVAILABLE,result.terminationReason());assertEquals(0,server.callCount());
        }
    }
    private AgentResult run(LoopbackMcpServer server,McpClientOperations client,ToolExecutionPolicy policy,String answer,AgentRunOptions options) {
        var properties=new McpProperties(true,List.of(new McpProperties.Server("demo",server.endpoint(),"",Set.of("project_info"))),Duration.ofSeconds(2));
        var provider=new McpToolProvider(properties,Map.of("demo",client),policy);
        var registry=new InMemoryToolRegistry(provider.tools());
        AgentModelClient model=request->request.iteration()==1?new ToolCallDecision("decision-1",
                new ToolCall("call-1","mcp.demo.project_info",new ToolArguments(Map.of())),TokenUsage.empty())
                :new FinalAnswerDecision("decision-2",answer,TokenUsage.empty());
        var runtime=new DefaultAgentRuntime(model,registry,new DefaultToolExecutor(registry),null,null,TimeSource.system(),
                new ContextAssembler(ContextPolicy.defaults(),new Utf8TokenEstimator(),new ContextTextPolicy()),policy);
        return runtime.run(new AgentRequest("demo-task","session","owner","read project"),AgentEventSink.NOOP,options);
    }

    @Test void disconnectedCallsRemainUnknownAndAreNeverRetried() {
        for(boolean write:List.of(false,true)) {
            ToolExecutionPolicy policy=write?ToolExecutionPolicy.rules(Map.of("mcp.demo.project_info",new ToolPolicyDecision(
                    ToolPolicyDecision.Action.REQUIRE_APPROVAL,RiskLevel.HIGH,ToolPolicyDecision.Effect.WRITE,"Append fixture",Set.of()))):McpToolProviderTest.POLICY;
            com.agentflow.core.approval.ApprovalGate gate=new com.agentflow.core.approval.ApprovalGate() {
                public com.agentflow.core.approval.ApprovalResolution await(com.agentflow.core.approval.ApprovalRequest request,ToolExecutionControl control) {
                    return new com.agentflow.core.approval.ApprovalResolution(request.approvalId(),com.agentflow.core.approval.ApprovalStatus.APPROVED,
                            java.time.Instant.now(),com.agentflow.core.approval.ApprovalResolution.DecisionSource.USER,0);
                }
                public boolean claimDispatch(com.agentflow.core.approval.ApprovalRequest request,ToolExecutionControl control){return true;}
            };
            try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().dropAfterCallHeaders());var client=SdkMcpClientOperationsTest.client(server)) {
                var result=run(server,client,policy,"unused",new AgentRunOptions(ExecutionBudget.defaults(),com.agentflow.core.cancel.CancellationSignal.NONE,gate));
                assertEquals(ToolInvocationRecord.Outcome.UNKNOWN,result.toolInvocations().get(0).outcome());
                // The body ends after HTTP headers: the delegated parser now observes incomplete JSON.
                assertEquals(write?TerminationReason.AMBIGUOUS_TOOL_OUTCOME:TerminationReason.MCP_PROTOCOL_ERROR,result.terminationReason());
                assertEquals(1,server.callCount());assertEquals(1,result.toolInvocations().get(0).dispatchCount());
            }
        }
    }
}
