package com.agentflow.core.runtime;

import com.agentflow.core.*;
import com.agentflow.core.approval.*;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.agentflow.core.tool.ToolPolicyDecision.*;

class RuntimeApprovalTest {
    static final ToolDefinition DEFINITION = new ToolDefinition("note", "Write note", RiskLevel.LOW,
            new ToolSchema(Map.of("body",ParameterSpec.requiredString()),Set.of("body"),false));
    static final ToolCall CALL = new ToolCall("call-1","note",new ToolArguments(Map.of("body","private-body-unique")));
    static final ToolPolicyDecision APPROVAL = new ToolPolicyDecision(Action.REQUIRE_APPROVAL,RiskLevel.HIGH,Effect.WRITE,"Append note",Set.of());
    static final ToolPolicyDecision ALLOW = new ToolPolicyDecision(Action.ALLOW,RiskLevel.LOW,Effect.READ_ONLY,"Read note",Set.of());
    static final AgentRequest REQUEST = new AgentRequest("demo-task","session","owner","perform note operation");
    static class Registry implements ToolRegistry {
        ToolRegistration registration; Registry(AgentTool tool) { registration=new ToolRegistration(tool,true); }
        public void register(ToolRegistration value) { registration=value; }
        public ToolLookup lookup(String name) { return name.equals("note") ? new ToolLookup(ToolAvailability.ENABLED,registration) : ToolLookup.unknown(); }
        public List<ToolDefinition> enabledDefinitions() { return List.of(registration.definition()); }
    }
    static AgentTool tool(AtomicInteger calls) {
        return new AgentTool() {
            public ToolDefinition definition() { return DEFINITION; }
            public ToolResult execute(ToolArguments args, ToolContext context) {
                assertEquals(CALL.arguments().values(),args.values()); calls.incrementAndGet();
                return ToolResult.success("note","stored");
            }
        };
    }
    static DefaultAgentRuntime runtime(Registry registry, ToolExecutionPolicy policy, AtomicInteger modelCalls, TimeSource time) {
        AgentModelClient model = request -> {
            modelCalls.incrementAndGet();
            return request.iteration()==1 ? new ToolCallDecision("decision-1",CALL,TokenUsage.empty())
                    : new FinalAnswerDecision("decision-2","done",TokenUsage.empty());
        };
        return new DefaultAgentRuntime(model,registry,new DefaultToolExecutor(registry),null,null,time,
                new ContextAssembler(ContextPolicy.defaults(),new Utf8TokenEstimator(),new ContextTextPolicy()),policy);
    }
    static ApprovalGate gate(java.util.function.Consumer<ApprovalRequest> action) {
        return new ApprovalGate() {
            public ApprovalResolution await(ApprovalRequest request,ToolExecutionControl control) {
                action.accept(request);
                return new ApprovalResolution(request.approvalId(),ApprovalStatus.APPROVED,request.createdAt(),ApprovalResolution.DecisionSource.USER,3);
            }
            public boolean claimDispatch(ApprovalRequest request,ToolExecutionControl control) { return true; }
        };
    }
    @Test void unknownPolicyDeniesAndMissingGateNeverExecutes() {
        for(var policy: List.of(ToolExecutionPolicy.denyAll(),ToolExecutionPolicy.rules(Map.of("note",APPROVAL)))) {
            var calls=new AtomicInteger(); var model=new AtomicInteger();
            var result=runtime(new Registry(tool(calls)),policy,model,TimeSource.system()).run(REQUEST,AgentEventSink.NOOP);
            assertEquals(0,calls.get()); assertEquals(1,model.get());
            assertTrue(Set.of("TOOL_POLICY_DENIED","APPROVAL_UNAVAILABLE").contains(result.terminationReason().name()));
            assertEquals(1,result.toolInvocations().size()); assertEquals(0,result.toolInvocations().get(0).dispatchCount());
        }
    }
    @Test void approvedCallUsesSameArgumentsAndProducesBoundRecord() {
        var calls=new AtomicInteger(); var models=new AtomicInteger();
        var result=runtime(new Registry(tool(calls)),ToolExecutionPolicy.rules(Map.of("note",APPROVAL)),models,TimeSource.system())
                .run(REQUEST,AgentEventSink.NOOP,new AgentRunOptions(ExecutionBudget.defaults(),CancellationSignal.NONE,gate(req->{
                    assertEquals(1,models.get()); assertEquals(0,calls.get()); assertEquals(CALL.arguments().values(),req.preparedCall().call().arguments().values());
                })));
        assertEquals(RunStatus.SUCCEEDED,result.status()); assertEquals(1,calls.get());
        var record=result.toolInvocations().get(0);
        assertEquals("demo-task",record.runId()); assertEquals("call-1",record.callId());
        assertEquals(APPROVAL.policyVersion(),record.policyVersion());
        assertEquals(ToolArgumentDigest.digest(CALL.arguments()),record.argumentsDigest());
        assertEquals(ApprovalStatus.APPROVED,record.approvalStatus()); assertEquals(1,record.dispatchCount());
        assertNotNull(record.dispatchAt()); assertEquals(3L,record.approvalWaitMillis());
    }
    @Test void registryReplacementAndPolicyChangeInvalidateApproval() {
        for(boolean replaceTool:List.of(true,false)) {
            var calls=new AtomicInteger(); var registry=new Registry(tool(calls));
            var policy=new AtomicReference<>(APPROVAL);
            var result=runtime(registry,name->policy.get(),new AtomicInteger(),TimeSource.system()).run(REQUEST,AgentEventSink.NOOP,
                    new AgentRunOptions(ExecutionBudget.defaults(),CancellationSignal.NONE,gate(req->{
                        if(replaceTool) registry.register(new ToolRegistration(tool(calls),true));
                        else policy.set(ToolPolicyDecision.denied());
                    })));
            assertEquals("APPROVAL_STALE",result.terminationReason().name()); assertEquals(0,calls.get());
            assertEquals(ApprovalStatus.APPROVED,result.toolInvocations().get(0).approvalStatus());
        }
    }
    @Test void cancellationAfterApprovalPreservesDecisionWithoutDispatch() {
        var cancelled=new AtomicBoolean();var calls=new AtomicInteger();
        var result=runtime(new Registry(tool(calls)),name->APPROVAL,new AtomicInteger(),TimeSource.system()).run(REQUEST,AgentEventSink.NOOP,
                new AgentRunOptions(ExecutionBudget.defaults(),cancelled::get,gate(req->cancelled.set(true))));
        assertEquals(RunStatus.CANCELLED,result.status()); assertEquals(0,calls.get());
        assertEquals(ApprovalStatus.APPROVED,result.toolInvocations().get(0).approvalStatus());
    }
    @Test void noRawArgumentsAppearInTrace() {
        var calls=new AtomicInteger();
        var result=runtime(new Registry(tool(calls)),name->ALLOW,new AtomicInteger(),TimeSource.system()).run(REQUEST,AgentEventSink.NOOP);
        assertFalse(result.steps().toString().contains("private-body-unique"));
    }

    @Test void approvalTimeConsumesRunBudgetAndTtlUsesMonotonicClock() {
        for(boolean totalTimeout:List.of(true,false)) {
            var ticks=new AtomicLong();var calls=new AtomicInteger();
            var budget=new ExecutionBudget(8,java.time.Duration.ofSeconds(totalTimeout?2:10),4096,2048);
            var result=runtime(new Registry(tool(calls)),name->APPROVAL,new AtomicInteger(),ticks::get)
                    .run(REQUEST,AgentEventSink.NOOP,new AgentRunOptions(budget,CancellationSignal.NONE,
                            gate(req->ticks.set(java.time.Duration.ofSeconds(3).toNanos())),java.time.Duration.ofSeconds(2)));
            assertEquals(totalTimeout?"TIMED_OUT":"APPROVAL_TIMEOUT",result.terminationReason().name());
            assertEquals(0,calls.get()); assertEquals(ApprovalStatus.APPROVED,result.toolInvocations().get(0).approvalStatus());
        }
    }
    @Test void compatibilityConstructorDeniesByDefault() {
        var calls=new AtomicInteger();var registry=new Registry(tool(calls));
        var result=new DefaultAgentRuntime(req->new ToolCallDecision("decision",CALL,TokenUsage.empty()),registry,null)
                .run(REQUEST,AgentEventSink.NOOP);
        assertEquals("TOOL_POLICY_DENIED",result.terminationReason().name());assertEquals(0,calls.get());
    }
    @Test void failedDispatchClaimCannotCallTool() {
        var calls=new AtomicInteger();var approved=gate(req->{});
        ApprovalGate gate=new ApprovalGate() {
            public ApprovalResolution await(ApprovalRequest request,ToolExecutionControl control) {return approved.await(request,control);}
            public boolean claimDispatch(ApprovalRequest request,ToolExecutionControl control) {return false;}
        };
        var result=runtime(new Registry(tool(calls)),name->APPROVAL,new AtomicInteger(),TimeSource.system())
                .run(REQUEST,AgentEventSink.NOOP,new AgentRunOptions(ExecutionBudget.defaults(),CancellationSignal.NONE,gate));
        assertEquals(0,calls.get());assertEquals(0,result.toolInvocations().get(0).dispatchCount());
    }
    @Test void waitingDoesNotCallModelOrTool() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var calls=new AtomicInteger();var models=new AtomicInteger();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(()->runtime(new Registry(tool(calls)),name->APPROVAL,models,TimeSource.system())
                    .run(REQUEST,AgentEventSink.NOOP,new AgentRunOptions(ExecutionBudget.defaults(),CancellationSignal.NONE,gate(req->{
                        entered.countDown();try {if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("release missing");}
                        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                    }))));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,models.get());assertEquals(0,calls.get());
            release.countDown();assertEquals(RunStatus.SUCCEEDED,result.get(2,java.util.concurrent.TimeUnit.SECONDS).status());
        } finally {release.countDown();executor.shutdownNow();}
    }
}
