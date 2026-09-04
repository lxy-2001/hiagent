package com.agentflow.core.runtime;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.cancel.CancellationSignal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPolicyContractTest {

    @Test
    void defaultsAreBoundedAndZeroIsValidBoundary() {
        ExecutionBudget defaults = ExecutionBudget.defaults();
        assertEquals(8, defaults.maxIterations());
        assertEquals(Duration.ofSeconds(30), defaults.maxDuration());
        assertEquals(4096, defaults.maxPromptTokens());
        assertEquals(2048, defaults.maxCompletionTokens());
        assertEquals(0, new ExecutionBudget(0, Duration.ZERO, 0, 0).maxIterations());
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionBudget(-1, Duration.ZERO, 0, 0));
    }

    @Test
    void runOptionsRequireBudgetAndCancellationSignal() {
        AgentRunOptions options = AgentRunOptions.defaults();
        assertEquals(CancellationSignal.NONE, options.cancellationSignal());
        assertThrows(NullPointerException.class,
                () -> new AgentRunOptions(null, CancellationSignal.NONE));
        assertThrows(NullPointerException.class,
                () -> new AgentRunOptions(ExecutionBudget.defaults(), null));
        assertFalse(CancellationSignal.NONE.isCancelled());
    }

    @Test
    void resultBoundaryRedactsSensitiveAnswerAndDiagnostic() {
        AgentResult success = AgentResult.success("t", "apiKey=secret-value",
                List.of(), TokenUsage.empty());
        assertFalse(success.finalAnswer().contains("secret-value"));
        assertTrue(success.finalAnswer().contains("[redacted]"));

        AgentResult failure = AgentResult.failure("t", RunStatus.FAILED,
                TerminationReason.MODEL_ERROR, "Bearer bearer-secret password=pw",
                List.of(), TokenUsage.empty());
        assertFalse(failure.diagnostic().contains("bearer-secret"));
        assertFalse(failure.diagnostic().contains("pw"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentResult("t", null, List.of(), RunStatus.FAILED,
                        TerminationReason.COMPLETED, TokenUsage.empty(), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentResult("t", null, List.of(), RunStatus.CANCELLED,
                        TerminationReason.MODEL_ERROR, TokenUsage.empty(), ""));
    }

    @Test
    void preservesLongSuccessAnswerWhileRedactingSensitiveValues() {
        String answer = "x".repeat(2_000) + " apiKey=secret-value";
        AgentResult result = AgentResult.success("t", answer, List.of(), TokenUsage.empty());

        assertTrue(result.finalAnswer().length() > 1_024);
        assertFalse(result.finalAnswer().contains("secret-value"));
        assertTrue(result.finalAnswer().contains("[redacted]"));
    }

    @Test
    void requestRejectsBlankInputAndFailureResultCannotLookSuccessful() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRequest("t", "s", "u", "  "));
        AgentResult result = AgentResult.failure("t", RunStatus.FAILED,
                TerminationReason.MODEL_ERROR, "safe diagnostic", List.of(), TokenUsage.empty());
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(TerminationReason.MODEL_ERROR, result.terminationReason());
        assertTrue(result.finalAnswer() == null || result.finalAnswer().isBlank());
    }
}
