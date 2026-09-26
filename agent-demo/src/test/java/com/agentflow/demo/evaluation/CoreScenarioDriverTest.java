package com.agentflow.demo.evaluation;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.eval.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoreScenarioDriverTest {
    private EvalCase scenario(String id) throws Exception {
        var data = EvalDataset.load(getClass().getResourceAsStream("/evaluation/dataset-v1.json").readAllBytes());
        return data.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
    @Test void assemblerObservesActualSuccessfulAndRejectedAssemblies() {
        var assembler = new ObservingContextAssembler(ContextPolicy.defaults());
        var request = new AgentRequest("r", "s", "u", "hello");
        var result = assembler.assemble(request, List.of(ModelMessage.user("hello")), List.of(), 1, 100);
        assertTrue(result.ready()); assertEquals(List.of(result.diagnostics()), assembler.diagnostics());
        var limited = new ObservingContextAssembler(new ContextPolicy(ContextPolicy.DEFAULT_SYSTEM, "test", 1));
        var rejected = limited.assemble(request, List.of(ModelMessage.user("hello")), List.of(), 1, 100);
        assertFalse(rejected.ready()); assertEquals(List.of(rejected.diagnostics()), limited.diagnostics());
    }
    @Test void recordsThrownAttemptsAndReturnedSourceWithoutChangingDecision() {
        var client = new ObservedModelClient(request -> { throw new IllegalStateException("controlled"); });
        assertThrows(IllegalStateException.class, () -> client.decide(null));
        assertEquals(1, client.attempts().size()); assertNull(client.attempts().get(0).usage());
        var decision = new FinalAnswerDecision("d", "OK", TokenUsage.empty(), UsageSource.FIXTURE);
        var returning = new ObservedModelClient(request -> decision);
        assertSame(decision, returning.decide(null));
        assertEquals(UsageSource.FIXTURE, returning.attempts().get(0).source());
    }
    @Test void executesInitialLocalCasesThroughRealRuntimeWithFreshState() throws Exception {
        for (String id : List.of("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C21", "C26")) {
            var c = scenario(id);
            try (var driver = new CoreScenarioDriver()) {
                var observation = driver.execute(c, EvalVariant.baseline(), 1);
                var score = new EvaluationScorer().score(c, observation);
                assertEquals(CaseReport.Status.PASS, score.status(), id + " " + score.assertions());
                assertTrue(EvaluationScorer.traceComplete(observation), id);
                assertEquals("KNOWN", observation.metrics().get("runtimeTotal").status());
                assertEquals("NOT_APPLICABLE", observation.metrics().get("queueWait").status());
            }
        }
    }
    @Test void retrievalCasesBindActualSourcesAndKeepFailureDistinctFromEmpty() throws Exception {
        for (String id : List.of("C09", "C10", "C11", "C12", "C13", "C17", "C20")) {
            var c = scenario(id);
            try (var driver = new CoreScenarioDriver()) {
                var observation = driver.execute(c, EvalVariant.baseline(), 1);
                var score = new EvaluationScorer().score(c, observation);
                assertEquals(CaseReport.Status.PASS, score.status(), id + " " + score.assertions());
                if (id.equals("C13")) assertTrue(observation.result().steps().stream().anyMatch(s -> "RAG_SOURCE_INVALID".equals(s.errorCode())));
                if (id.equals("C09")) assertEquals("Java 21", observation.result().finalAnswer());
                if (id.equals("C10")) assertEquals("S1", observation.result().citations().get(0).id());
                if (id.equals("C11") || id.equals("C12")) assertNull(observation.result().finalAnswer());
            }
        }
    }
    @Test void untrustedToolTextAndMissingProviderUsageAreObserved() throws Exception {
        for (String id : List.of("C24", "C25")) {
            try (var driver = new CoreScenarioDriver()) {
                var c = scenario(id);
                var observation = driver.execute(c, EvalVariant.baseline(), 1);
                assertEquals(CaseReport.Status.PASS, new EvaluationScorer().score(c, observation).status());
                if (id.equals("C25")) assertEquals("UNKNOWN", EvaluationMetrics.usage(observation.attempts()).status());
            }
        }
    }
    @Test void mcpReadAndCancellationUseActualSdkDispatch() throws Exception {
        for (String id : List.of("C14", "C19")) {
            try (var driver = new CoreScenarioDriver()) {
                var c = scenario(id);
                var observed = driver.execute(c, EvalVariant.baseline(), 1);
                assertEquals(CaseReport.Status.PASS, new EvaluationScorer().score(c, observed).status(), id + " " + new EvaluationScorer().score(c, observed));
                assertEquals(1, observed.dispatchCount());
                if (id.equals("C19")) assertEquals(com.agentflow.core.tool.ToolInvocationRecord.Outcome.UNKNOWN,
                        observed.result().toolInvocations().get(0).outcome());
            }
        }
    }
    @Test void compactWindowActuallyFailsTheFrozenMandatoryInput() throws Exception {
        try (var driver = new CoreScenarioDriver()) {
            var observation = driver.execute(scenario("C26"), EvalVariant.compactContext(), 1);
            assertEquals(com.agentflow.core.runtime.TerminationReason.CONTEXT_BUDGET_EXCEEDED, observation.result().terminationReason());
            assertTrue(observation.contextDiagnostics().get(0).mandatory() >= 5000);
            assertTrue(observation.contextDiagnostics().get(0).mandatory() <= 6000);
            assertEquals(0, observation.attempts().size());
        }
    }
}
