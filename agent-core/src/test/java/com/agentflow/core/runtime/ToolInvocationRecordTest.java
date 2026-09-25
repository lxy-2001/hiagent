package com.agentflow.core.runtime;

import com.agentflow.core.*;
import com.agentflow.core.approval.*;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.tool.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolInvocationRecordTest {
    @Test void ordinaryCallFactsCarryOriginalIdentityAndPolicy() {
        for(var decision:List.of(RuntimeApprovalTest.ALLOW,RuntimeApprovalTest.APPROVAL,ToolPolicyDecision.denied())) {
            var calls=new AtomicInteger();
            var result=RuntimeApprovalTest.runtime(new RuntimeApprovalTest.Registry(RuntimeApprovalTest.tool(calls)),
                    name->decision,new AtomicInteger(),TimeSource.system()).run(RuntimeApprovalTest.REQUEST,AgentEventSink.NOOP);
            var record=result.toolInvocations().get(0);
            assertEquals("demo-task",record.runId());assertEquals(decision.effect(),record.effect());
            assertEquals(decision.policyVersion(),record.policyVersion());assertNotNull(record.createdAt());
            assertEquals(ToolArgumentDigest.digest(RuntimeApprovalTest.CALL.arguments()),record.argumentsDigest());
            assertEquals(ToolArgumentDigest.definitionVersion(RuntimeApprovalTest.DEFINITION),record.definitionVersion());
            assertNull(record.approvalId());assertNull(record.approvalStatus());assertNull(record.approvalWaitMillis());
            if(calls.get()==0) {assertNull(record.dispatchAt());assertNull(record.executionMillis());}
            else {assertNotNull(record.dispatchAt());assertNotNull(record.executionMillis());}
        }
    }
    @Test void cancellationRetainsUnknownWriteOutcomeAndNeverRetries() {
        var cancelled=new AtomicBoolean();var calls=new AtomicInteger();
        AgentTool tool=new AgentTool() {
            public ToolDefinition definition(){return RuntimeApprovalTest.DEFINITION;}
            public ToolResult execute(ToolArguments args,ToolContext context){
                calls.incrementAndGet();cancelled.set(true);throw new IllegalStateException("private-body-unique");
            }
        };
        var result=RuntimeApprovalTest.runtime(new RuntimeApprovalTest.Registry(tool),name->RuntimeApprovalTest.APPROVAL,
                new AtomicInteger(),TimeSource.system()).run(RuntimeApprovalTest.REQUEST,AgentEventSink.NOOP,
                new AgentRunOptions(ExecutionBudget.defaults(),cancelled::get,RuntimeApprovalTest.gate(req->{})));
        assertEquals(RunStatus.CANCELLED,result.status());assertEquals(1,calls.get());
        var record=result.toolInvocations().get(0);
        assertEquals(ToolInvocationRecord.Outcome.UNKNOWN,record.outcome());assertEquals(1,record.dispatchCount());
        assertEquals(ApprovalStatus.APPROVED,record.approvalStatus());
        assertFalse(result.toString().contains("private-body-unique"));
    }
    @Test void completedWriteRemainsSuccessfulEvenWhenRunIsCancelled() {
        var cancelled=new AtomicBoolean();
        AgentTool tool=new AgentTool() {
            public ToolDefinition definition(){return RuntimeApprovalTest.DEFINITION;}
            public ToolResult execute(ToolArguments args,ToolContext context){cancelled.set(true);return ToolResult.success("note","receipt");}
        };
        var result=RuntimeApprovalTest.runtime(new RuntimeApprovalTest.Registry(tool),name->RuntimeApprovalTest.APPROVAL,
                new AtomicInteger(),TimeSource.system()).run(RuntimeApprovalTest.REQUEST,AgentEventSink.NOOP,
                new AgentRunOptions(ExecutionBudget.defaults(),cancelled::get,RuntimeApprovalTest.gate(req->{})));
        assertEquals(RunStatus.CANCELLED,result.status());assertEquals(ToolInvocationRecord.Outcome.SUCCEEDED,result.toolInvocations().get(0).outcome());
        assertThrows(UnsupportedOperationException.class,()->result.toolInvocations().clear());
    }

    @Test void invocationOrderAndCountFollowIterationBudget() {
        var calls=new AtomicInteger();var registry=new RuntimeApprovalTest.Registry(RuntimeApprovalTest.tool(calls));
        var runtime=new DefaultAgentRuntime(request->new com.agentflow.core.model.ToolCallDecision("decision-"+request.iteration(),
                new ToolCall("call-"+request.iteration(),"note",RuntimeApprovalTest.CALL.arguments()),com.agentflow.core.chat.TokenUsage.empty()),
                registry,new DefaultToolExecutor(registry),null,null,TimeSource.system(),
                new com.agentflow.core.context.ContextAssembler(com.agentflow.core.context.ContextPolicy.defaults(),new com.agentflow.core.context.Utf8TokenEstimator(),new com.agentflow.core.context.ContextTextPolicy()),
                ToolExecutionPolicy.rules(Map.of("note",RuntimeApprovalTest.ALLOW)));
        var result=runtime.run(RuntimeApprovalTest.REQUEST,AgentEventSink.NOOP);
        assertEquals(RunStatus.BUDGET_EXCEEDED,result.status());assertEquals(8,calls.get());
        assertEquals(java.util.stream.IntStream.rangeClosed(1,8).mapToObj(i->"call-"+i).toList(),result.toolInvocations().stream().map(ToolInvocationRecord::callId).toList());
    }
}
