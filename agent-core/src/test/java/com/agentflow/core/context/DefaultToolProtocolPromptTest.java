package com.agentflow.core.context;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.model.ModelMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DefaultToolProtocolPromptTest {
    @Test
    void singleToolProtocolIsInSystemMessageAndConsumesWindowBudget() {
        var policy = ContextPolicy.defaults();
        var estimator = new Utf8TokenEstimator();
        var request = new AgentRequest("r", "s", "u", "question");
        var chain = List.of(ModelMessage.user("question"));
        var result = new ContextAssembler(policy, estimator, new ContextTextPolicy())
                .assemble(request, chain, List.of(), 1, 10);
        assertTrue(result.ready());
        var system = result.request().messages().get(0);
        assertEquals("system", system.role());
        assertTrue(system.content().contains("每轮最多调用一个工具"));
        assertTrue(system.content().contains("等待工具结果"));
        long input = estimator.estimateInput(result.request().messages(), List.of());
        assertEquals(input, result.diagnostics().estimatedInput());
        var tight = new ContextPolicy(policy.systemText(), policy.promptVersion(), input - 1);
        assertFalse(new ContextAssembler(tight, estimator, new ContextTextPolicy())
                .assemble(request, chain, List.of(), 1, 0).ready());
    }
}
