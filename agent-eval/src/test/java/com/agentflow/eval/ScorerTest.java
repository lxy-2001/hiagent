package com.agentflow.eval;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.runtime.DefaultAgentRuntime;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScorerTest {
    private EvalCase scenario(String id) throws Exception {
        var data = EvalDataset.load(getClass().getResourceAsStream("/evaluation/dataset-v1.json").readAllBytes());
        return data.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
    private AgentResult run(List<AgentEvent> events) {
        ToolRegistry registry = new ToolRegistry() {
            public void register(ToolRegistration registration) { throw new UnsupportedOperationException(); }
            public ToolLookup lookup(String name) { throw new UnsupportedOperationException(); }
            public List<ToolDefinition> enabledDefinitions() { return List.of(); }
        };
        return new DefaultAgentRuntime(r -> new FinalAnswerDecision("d", "OK", TokenUsage.empty()), registry, null)
                .run(new AgentRequest("run", "session", "owner", "Say OK"), events::add);
    }
    private ObservedCase observed(Map<EvalCase.Rule, ObservedCase.Fact> facts, boolean complete) {
        var events = new ArrayList<AgentEvent>();
        var result = run(events);
        return new ObservedCase(result, events, List.of(), List.of(), complete, 0, facts, null, null);
    }
    @Test void scoresActualRuntimeAndMissingEvidenceCannotPass() throws Exception {
        var facts = Map.of(EvalCase.Rule.NO_SECRET, new ObservedCase.Fact(List.of(), List.of()),
                EvalCase.Rule.ANSWER_MARKER, new ObservedCase.Fact("OK", "OK"));
        assertEquals(CaseReport.Status.PASS, new EvaluationScorer().score(scenario("C01"), observed(facts, true)).status());
        assertEquals(CaseReport.Status.INCOMPLETE, new EvaluationScorer().score(scenario("C01"), observed(Map.of(), true)).status());
        assertEquals(CaseReport.Status.INCOMPLETE, new EvaluationScorer().score(scenario("C01"), observed(facts, false)).status());
    }
    @Test void wrongToolArgumentsCitationWritesAndSecretsCannotBeHiddenBySuccess() throws Exception {
        for (var rule : List.of(EvalCase.Rule.TOOL_SEQUENCE, EvalCase.Rule.ARGUMENTS_MATCH,
                EvalCase.Rule.CITATIONS_BOUND, EvalCase.Rule.WRITE_COUNT, EvalCase.Rule.NO_SECRET)) {
            var base = scenario("C01");
            var expectation = new EvalCase.Expectations(base.expectations().runStatus(), "COMPLETED", 0,
                    List.of(EvalCase.Rule.TERMINAL, EvalCase.Rule.TRACE_COMPLETE, rule), List.of(EvalCase.Rule.ANSWER_MARKER));
            var c = new EvalCase(base.id(), base.driver(), base.fixtureId(), base.input(), base.budget(), expectation);
            var facts = Map.of(rule, new ObservedCase.Fact("corrupt", "required"),
                    EvalCase.Rule.ANSWER_MARKER, new ObservedCase.Fact("OK", "OK"));
            assertEquals(CaseReport.Status.FAIL, new EvaluationScorer().score(c, observed(facts, true)).status(), rule.name());
        }
    }
    @Test void contextSourceFailureUsesWebEvidenceWithoutInventingCoreResult() throws Exception {
        var lifecycle = List.of(new ObservedCase.LifecycleEvent("web-run", 1, "RUN_CREATED", null, null),
                new ObservedCase.LifecycleEvent("web-run", 2, "RUN_STARTED", null, null),
                new ObservedCase.LifecycleEvent("web-run", 3, "RUN_TERMINATED", "FAILED", "CONTEXT_SOURCE_UNAVAILABLE"));
        for (int mode = 0; mode < 4; mode++) {
            var events = mode == 1 ? lifecycle.subList(0, 2) : mode == 2 ? lifecycle.subList(1, 3) : lifecycle;
            var trace = new ObservedCase.WebTrace("web-run", List.of(), false, events, mode == 3 ? 1 : 0);
            var observed = new ObservedCase(null, List.of(), List.of(), List.of(), true, 0,
                    Map.of(EvalCase.Rule.NO_SECRET, new ObservedCase.Fact(List.of(), List.of())),
                    "FAILED", "CONTEXT_SOURCE_UNAVAILABLE", trace);
            assertEquals(mode == 0 ? CaseReport.Status.PASS : CaseReport.Status.INCOMPLETE,
                    new EvaluationScorer().score(scenario("C23"), observed).status());
        }
    }
    @Test void allDeclaredRulesRequireEvidenceAcrossAllTwentySixCases() throws Exception {
        var dataset = EvalDataset.load(getClass().getResourceAsStream("/evaluation/dataset-v1.json").readAllBytes());
        for (var c : dataset.cases()) {
            var report = new EvaluationScorer().score(c, observed(Map.of(), true));
            assertNotEquals(CaseReport.Status.PASS, report.status(), c.id());
            assertEquals(c.expectations().hardRules().size() + c.expectations().taskRules().size(), report.assertions().size());
        }
    }
}
