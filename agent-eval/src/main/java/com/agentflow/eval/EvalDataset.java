package com.agentflow.eval;

import com.agentflow.core.runtime.ExecutionBudget;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record EvalDataset(String datasetVersion, String fixtureVersion, String sha256, List<EvalCase> cases) {
    private static final List<String> FIXTURES = List.of("final-answer", "local-tool", "two-tools",
            "unknown-tool", "invalid-arguments", "policy-denied", "history-trimming", "memory-update",
            "memory-precedence", "valid-citation", "forged-citation", "empty-evidence", "retrieval-failure",
            "mcp-read", "approval-approved", "approval-rejected", "approval-expired", "cancel-waiting",
            "cancel-dispatched", "run-timeout", "iteration-budget", "capacity", "context-source-failure",
            "untrusted-content", "usage-missing", "window-sensitive");
    private static final Set<Integer> HTTP = Set.of(8, 15, 16, 18, 22, 23);
    public EvalDataset {
        cases = List.copyOf(cases);
        if (cases.size() != 26 || new HashSet<>(cases.stream().map(EvalCase::id).toList()).size() != 26) {
            throw invalid();
        }
        for (int i = 1; i <= 26; i++) {
            String id = "C%02d".formatted(i);
            var c = cases.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(EvalDataset::invalid);
            if (!c.fixtureId().equals(FIXTURES.get(i - 1))
                    || c.driver() != (HTTP.contains(i) ? EvalCase.Driver.HTTP : EvalCase.Driver.CORE)) throw invalid();
        }
    }
    public static EvalDataset load(byte[] bytes) {
        if (bytes == null || bytes.length > 1048576) throw invalid();
        try {
            JsonNode root = EvalJson.MAPPER.readTree(bytes);
            fields(root, "schemaVersion", "datasetVersion", "fixtureVersion", "cases");
            if (integer(root, "schemaVersion", 1) != 1) throw invalid();
            var casesNode = root.path("cases");
            if (!casesNode.isArray() || casesNode.size() > 100) throw invalid();
            var cases = new ArrayList<EvalCase>();
            for (var c : casesNode) {
                fields(c, "id", "driver", "fixtureId", "input", "budget", "expectations");
                var b = c.path("budget");
                fields(b, "maxIterations", "maxDurationMs", "maxPromptTokens", "maxCompletionTokens");
                var e = c.path("expectations");
                fields(e, "runStatus", "terminationReason", "dispatchCount", "hardRules", "taskRules");
                String reason = text(e, "terminationReason", 128);
                if (!reason.equals("CONTEXT_SOURCE_UNAVAILABLE")) TerminationReason.valueOf(reason);
                cases.add(new EvalCase(text(c, "id", 3), EvalCase.Driver.valueOf(text(c, "driver", 4)),
                        text(c, "fixtureId", 128), text(c, "input", 8192),
                        new ExecutionBudget(integer(b, "maxIterations", 16), Duration.ofMillis(integer(b, "maxDurationMs", 15000)),
                                integer(b, "maxPromptTokens", 16384), integer(b, "maxCompletionTokens", 16384)),
                        new EvalCase.Expectations(RunStatus.valueOf(text(e, "runStatus", 32)), reason,
                                integer(e, "dispatchCount", Integer.MAX_VALUE), rules(e.path("hardRules")), rules(e.path("taskRules")))));
            }
            return new EvalDataset(text(root, "datasetVersion", 128), text(root, "fixtureVersion", 128), EvalJson.hash(root), cases);
        } catch (RuntimeException ex) {
            // Do not retain parser messages: they can contain the input document.
            throw invalid();
        }
    }
    private static List<EvalCase.Rule> rules(JsonNode node) {
        if (!node.isArray() || node.size() > 20) throw invalid();
        var result = new ArrayList<EvalCase.Rule>();
        for (var n : node) { if (!n.isTextual()) throw invalid(); result.add(EvalCase.Rule.valueOf(n.asText())); }
        return result;
    }
    static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) throw invalid();
        for (String name : names) if (!node.has(name)) throw invalid();
    }
    private static String text(JsonNode node, String field, int max) {
        var n = node.path(field);
        if (!n.isTextual() || n.asText().isEmpty() || n.asText().length() > max) throw invalid();
        return n.asText();
    }
    private static int integer(JsonNode node, String field, int max) {
        var n = node.path(field);
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < 0 || n.asInt() > max) throw invalid();
        return n.asInt();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_EVALUATION_DATASET"); }
}
