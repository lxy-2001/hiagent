package com.agentflow.core.runtime;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContextTerminationContractTest {
    @Test
    void bothBudgetReasonsOnlyAcceptBudgetStatus() {
        for (var reason : List.of(TerminationReason.BUDGET_EXCEEDED, TerminationReason.CONTEXT_BUDGET_EXCEEDED)) {
            for (var status : RunStatus.values()) {
                if (status == RunStatus.BUDGET_EXCEEDED) {
                    assertDoesNotThrow(() -> AgentResult.failure("r", status, reason, "bounded", List.of(), TokenUsage.empty()));
                } else {
                    assertThrows(IllegalArgumentException.class, () -> new AgentResult("r", null, List.of(), status, reason, TokenUsage.empty(), "bounded"));
                }
            }
        }
    }
}
