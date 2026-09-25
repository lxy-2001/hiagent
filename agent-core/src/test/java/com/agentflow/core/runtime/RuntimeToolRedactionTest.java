package com.agentflow.core.runtime;

import com.agentflow.core.*;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeToolRedactionTest {
    @Test void failingToolCannotExposeItsArgumentThroughDiagnostic() {
        AgentTool tool=new AgentTool() {
            public ToolDefinition definition(){return RuntimeApprovalTest.DEFINITION;}
            public ToolResult execute(ToolArguments args,ToolContext context){return ToolResult.failure("note",null,"TOOL_ERROR","private-body-unique");}
        };
        var events=new ArrayList<AgentEvent>();
        var result=RuntimeApprovalTest.runtime(new RuntimeApprovalTest.Registry(tool),name->RuntimeApprovalTest.ALLOW,
                new AtomicInteger(),TimeSource.system()).run(RuntimeApprovalTest.REQUEST,events::add);
        assertFalse(result.steps().toString().contains("private-body-unique"));
        assertFalse(events.toString().contains("private-body-unique"));
        assertFalse(result.diagnostic().contains("private-body-unique"));
    }
    @Test void executorUsesAuthorizedObjectWhenRegistryChangesAfterAuthorization() {
        var originalCalls=new AtomicInteger();var replacementCalls=new AtomicInteger();
        var registry=new RuntimeApprovalTest.Registry(RuntimeApprovalTest.tool(originalCalls));
        var fixed=registry.registration;
        registry.register(new ToolRegistration(RuntimeApprovalTest.tool(replacementCalls),true));
        var result=new DefaultToolExecutor(registry).execute(RuntimeApprovalTest.CALL,new ToolContext("run","session","owner",List.of(),
                new ToolExecutionControl(com.agentflow.core.cancel.CancellationSignal.NONE,TimeSource.system(),java.time.Duration.ofSeconds(1)),fixed));
        assertEquals(ToolResultStatus.SUCCESS,result.status());assertEquals(1,originalCalls.get());assertEquals(0,replacementCalls.get());
    }

    @Test void normalizerExceptionDoesNotLeakRawArguments() {
        var registry=new RuntimeApprovalTest.Registry(RuntimeApprovalTest.tool(new AtomicInteger()));
        var runtime=new DefaultAgentRuntime(request->new com.agentflow.core.model.ToolCallDecision("decision",RuntimeApprovalTest.CALL,
                com.agentflow.core.chat.TokenUsage.empty()),registry,new DefaultToolExecutor(registry),null,
                (call,result)->{throw new IllegalArgumentException("private-body-unique");},TimeSource.system(),
                new com.agentflow.core.context.ContextAssembler(com.agentflow.core.context.ContextPolicy.defaults(),new com.agentflow.core.context.Utf8TokenEstimator(),new com.agentflow.core.context.ContextTextPolicy()),
                ToolExecutionPolicy.rules(Map.of("note",RuntimeApprovalTest.ALLOW)));
        var result=runtime.run(RuntimeApprovalTest.REQUEST,AgentEventSink.NOOP);
        assertEquals(TerminationReason.TOOL_RESULT_INVALID,result.terminationReason());
        assertFalse(result.steps().toString().contains("private-body-unique"));
    }
}
