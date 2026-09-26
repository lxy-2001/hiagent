package com.agentflow.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Safe report projection. Raw observations and fixture values never become JSON properties. */
public final class RunReport {
    public record Execution(String caseId, int repeat, ObservedCase observed, CaseReport score,
                            Map<String, EvaluationMetrics.Metric> metrics, List<String> supportingRunIds) {
        public Execution { metrics = Map.copyOf(metrics); supportingRunIds = List.copyOf(supportingRunIds); }
    }
    private final ObjectNode document;
    private RunReport(ObjectNode document) { this.document = document; }
    public JsonNode document() { return document.deepCopy(); }
    public String id() { return document.path("evaluationId").asText(); }
    public boolean passed() { return document.path("gateStatus").asText().equals("PASS"); }

    public static RunReport create(EvalDataset data, EvalVariant variant, int repeat, String mode,
                                   Map<String, Object> metadata, List<Execution> executions) {
        return create(data, variant, repeat, mode, metadata, executions, null);
    }
    public static RunReport create(EvalDataset data, EvalVariant variant, int repeat, String mode,
                                   Map<String, Object> metadata, List<Execution> executions, PriceSnapshot price) {
        if (repeat < 1 || repeat > 10 || !Set.of("OFFLINE_FIXTURE", "MODEL_LIVE").contains(mode)) throw invalid();
        var root = EvalJson.MAPPER.createObjectNode();
        root.put("schemaVersion", 1).put("evaluationId", UUID.randomUUID().toString()).put("mode", mode)
                .put("variant", variant.id()).put("repeat", repeat);
        var provenance = provenance(data, variant, metadata); root.set("provenance", provenance);
        var byKey = new HashMap<String, Execution>();
        for (var e : executions) {
            if (e.repeat() < 1 || e.repeat() > repeat || !data.cases().stream().anyMatch(c -> c.id().equals(e.caseId()))
                    || byKey.put(e.caseId() + "/" + e.repeat(), e) != null) throw invalid();
        }
        var cases = root.putArray("cases");
        var counts = new EnumMap<CaseReport.Status, Integer>(CaseReport.Status.class);
        int hardPassed = 0, hardApplicable = 0, hardIncomplete = 0;
        var selected = mode.equals("MODEL_LIVE") ? data.cases().stream().filter(c -> Set.of("C01", "C02").contains(c.id())).toList() : data.cases();
        if (mode.equals("MODEL_LIVE") && repeat > 3) throw invalid();
        for (var c : selected) for (int n = 1; n <= repeat; n++) {
            Execution execution = byKey.remove(c.id() + "/" + n);
            ObjectNode item = caseNode(c, n, execution, provenance.path("provider").asText(), provenance.path("model").asText(), price);
            cases.add(item);
            counts.merge(CaseReport.Status.valueOf(item.path("caseStatus").asText()), 1, Integer::sum);
            for (var assertion : item.path("assertions")) if (assertion.path("hard").asBoolean()) {
                if (!assertion.path("status").asText().equals("NOT_APPLICABLE")) hardApplicable++;
                if (assertion.path("status").asText().equals("PASS")) hardPassed++;
                if (assertion.path("status").asText().equals("INCOMPLETE")) hardIncomplete++;
            }
        }
        if (!byKey.isEmpty()) throw invalid();
        var summary = root.putObject("summary");
        summary.put("total", cases.size());
        String[] names = {"passed", "failed", "error", "incomplete", "skipped", "notRun"};
        int index = 0;
        for (var status : CaseReport.Status.values()) summary.put(names[index++], counts.getOrDefault(status, 0));
        summary.put("hardPassed", hardPassed).put("hardApplicable", hardApplicable).put("hardIncomplete", hardIncomplete)
                .put("taskSuccessRate", cases.isEmpty() ? 0 : counts.getOrDefault(CaseReport.Status.PASS, 0) / (double) cases.size());
        var latencies = summary.putArray("latencies");
        for (String name : METRIC_SCOPES.keySet()) {
            var scopes = new TreeSet<String>();
            for (var c : cases) scopes.add(c.path("metrics").path(name).path("scope").asText());
            for (var scope : scopes) {
                var values = new ArrayList<Double>(); int unknown = 0, na = 0;
                for (var c : cases) {
                    var metric = c.path("metrics").path(name);
                    if (!metric.path("scope").asText().equals(scope)) continue;
                    switch (metric.path("status").asText()) {
                        case "KNOWN" -> values.add(metric.path("value").asDouble());
                        case "UNKNOWN" -> unknown++;
                        default -> na++;
                    }
                }
                var distribution = EvaluationMetrics.distribution(values, "ms", scope);
                var out = latencies.addObject(); out.put("name", name).put("scope", scope).put("n", distribution.n())
                        .put("unknown", unknown).put("notApplicable", na).put("smallSample", distribution.smallSample());
                out.set("p50Ms", EvalJson.MAPPER.valueToTree(distribution.p50())); out.set("p95Ms", EvalJson.MAPPER.valueToTree(distribution.p95()));
            }
        }
        root.put("gateStatus", counts.getOrDefault(CaseReport.Status.PASS, 0) == cases.size() && hardPassed == hardApplicable ? "PASS" : "FAIL");
        root.putObject("externalExperiments").put("embedding", "NOT_RUN").put("thirdPartyMcp", "NOT_RUN");
        return new RunReport(root);
    }

    private static final Map<String, String> METRIC_SCOPES = Map.of("queueWait", "QUEUE", "runtimeTotal", "CORE",
            "approvalWait", "APPROVAL", "toolExecution", "TOOL", "modelCall", "MODEL");
    private static ObjectNode caseNode(EvalCase c, int repeat, Execution execution, String provider, String model, PriceSnapshot price) {
        var node = EvalJson.MAPPER.createObjectNode();
        node.put("caseId", c.id()).put("repeat", repeat).put("driver", c.driver().name());
        ObservedCase observed = execution == null ? null : execution.observed();
        var result = observed == null ? null : observed.result();
        var score = execution == null ? null : execution.score();
        if (score != null && !score.caseId().equals(c.id())) throw invalid();
        // Recompute the score to reject caller-supplied PASS or omitted hard assertions.
        if (observed != null && !new EvaluationScorer().score(c, observed).equals(score)) throw invalid();
        if (observed == null && score != null && !Set.of(CaseReport.Status.ERROR, CaseReport.Status.NOT_RUN).contains(score.status())) throw invalid();
        node.put("runId", result == null ? observed == null || observed.webTrace() == null ? null : safe(observed.webTrace().runId()) : safe(result.taskId()));
        var supporting = node.putArray("supportingRunIds");
        if (execution != null) execution.supportingRunIds().forEach(id -> supporting.add(safe(id)));
        node.put("caseStatus", score == null ? "NOT_RUN" : score.status().name());
        node.put("resultStatus", result == null ? observed == null ? null : safe(observed.webStatus()) : result.status().name());
        node.put("terminationReason", result == null ? observed == null ? null : safe(observed.webTerminationReason()) : result.terminationReason().name());
        node.put("recordingComplete", observed != null && (observed.webTrace() == null ? EvaluationScorer.traceComplete(observed) : observed.webTrace().recordingComplete()));
        var assertions = observed == null ? missingAssertions(c) : score.assertions();
        node.set("assertions", EvalJson.MAPPER.valueToTree(assertions));
        var steps = node.putArray("steps");
        var invocations = node.putArray("invocations");
        var citations = node.putArray("citations");
        var context = node.putArray("context");
        if (result != null) {
            for (var step : result.steps()) steps.addObject().put("stepNo", step.stepNo()).put("type", step.stepType().name())
                    .put("status", step.status().name()).put("toolName", safe(step.toolName())).put("callId", safe(step.callId()))
                    .put("terminal", step.terminal()).put("errorCode", safe(step.errorCode()));
            for (var call : result.toolInvocations()) invocations.addObject().put("callId", safe(call.callId()))
                    .put("toolName", safe(call.toolName())).put("dispatchCount", call.dispatchCount()).put("outcome", call.outcome().name())
                    .put("approvalStatus", call.approvalStatus() == null ? null : call.approvalStatus().name())
                    .put("definitionVersion", call.definitionVersion()).put("policyVersion", call.policyVersion()).put("errorCode", safe(call.errorCode()));
            boolean bound = score.assertions().stream().anyMatch(a -> a.id() == EvalCase.Rule.CITATIONS_BOUND && a.status() == AssertionResult.Status.PASS);
            for (var citation : result.citations()) citations.addObject().put("id", safe(citation.id())).put("chunkId", safe(citation.chunkId()))
                    .put("snapshotId", safe(citation.snapshotId())).put("valid", bound);
            for (var d : observed.contextDiagnostics()) context.addObject().put("iteration", d.iteration()).put("estimatedInput", d.estimatedInput())
                    .put("window", d.window()).put("keptTurns", d.keptTurns()).put("droppedTurns", d.droppedTurns())
                    .put("keptMemory", d.keptMemory()).put("droppedMemory", d.droppedMemory()).put("promptVersion", safe(d.promptVersion()))
                    .put("policyVersion", safe(d.policyVersion())).put("estimatorVersion", safe(d.estimatorVersion())).put("reason", safe(d.reason()));
        }
        if (result == null && observed != null && observed.webTrace() != null) {
            for (var step : observed.webTrace().steps()) steps.addObject().put("stepNo", step.stepNo()).put("type", safe(step.type()))
                    .put("status", safe(step.status())).put("toolName", safe(step.toolName())).put("callId", safe(step.callId()))
                    .put("terminal", step.terminal()).put("errorCode", safe(step.errorCode()));
        }
        var attempts = observed == null ? List.<EvaluationMetrics.Attempt>of() : observed.attempts();
        node.put("modelAttempts", attempts.size()).put("returnedCalls", attempts.stream().filter(a -> a.usage() != null).count());
        var usage = EvaluationMetrics.usage(attempts); node.set("usage", EvalJson.MAPPER.valueToTree(usage));
        node.set("modelCostEstimate", EvalJson.MAPPER.valueToTree(EvaluationMetrics.cost(usage, provider, model, price)));
        var metrics = node.putObject("metrics");
        if (execution != null && !METRIC_SCOPES.keySet().containsAll(execution.metrics().keySet())) throw invalid();
        for (var entry : METRIC_SCOPES.entrySet()) {
            var metric = execution == null ? null : execution.metrics().get(entry.getKey());
            if (metric == null) metric = new EvaluationMetrics.Metric("UNKNOWN", null, "ms",
                    entry.getKey().equals("runtimeTotal") && c.driver() == EvalCase.Driver.HTTP ? "WEB_ACTIVE" : entry.getValue());
            String expectedScope = entry.getKey().equals("runtimeTotal") && c.driver() == EvalCase.Driver.HTTP ? "WEB_ACTIVE" : entry.getValue();
            if (entry.getKey().equals("queueWait") && c.driver() == EvalCase.Driver.CORE) {
                metric = new EvaluationMetrics.Metric("NOT_APPLICABLE", null, "ms", "QUEUE");
            }
            if (!metric.unit().equals("ms") || !metric.scope().equals(expectedScope)) throw invalid();
            metrics.set(entry.getKey(), EvalJson.MAPPER.valueToTree(metric));
        }
        return node;
    }

    private static List<AssertionResult> missingAssertions(EvalCase definition) {
        var assertions = new ArrayList<AssertionResult>();
        for (var rule : definition.expectations().hardRules())
            assertions.add(new AssertionResult(rule, true, AssertionResult.Status.INCOMPLETE, "EVIDENCE_MISSING", null, null));
        for (var rule : definition.expectations().taskRules())
            assertions.add(new AssertionResult(rule, false, AssertionResult.Status.INCOMPLETE, "EVIDENCE_MISSING", null, null));
        return List.copyOf(assertions);
    }
    private static ObjectNode provenance(EvalDataset data, EvalVariant variant, Map<String, Object> metadata) {
        var expected = Set.of("codeSha", "codeStatus", "workingTreeDirty", "trackedDiffHash", "untrackedCount",
                "fixtureHash", "assertionHash", "promptHash", "policyHash", "configHash", "fixedConfigHash",
                "promptVersion", "policyVersion", "provider", "model", "adapterVersion");
        if (!metadata.keySet().equals(expected)) throw invalid();
        var node = EvalJson.MAPPER.createObjectNode();
        metadata.forEach((key, value) -> { if (value instanceof String text) safe(text); node.set(key, EvalJson.MAPPER.valueToTree(value)); });
        for (String key : Set.of("fixtureHash", "assertionHash", "promptHash", "policyHash", "configHash", "fixedConfigHash")) {
            if (!node.path(key).isTextual() || !node.path(key).asText().matches("[a-f0-9]{64}")) throw invalid();
        }
        if (!Set.of("KNOWN", "COPIED_SOURCE", "UNKNOWN").contains(node.path("codeStatus").asText())
                || !node.path("workingTreeDirty").isBoolean() || !node.path("untrackedCount").isIntegralNumber()
                || !node.path("untrackedCount").canConvertToInt() || node.path("untrackedCount").asInt() < 0) throw invalid();
        if (!node.path("codeSha").isNull() && !node.path("codeSha").asText().matches("[a-f0-9]{40}")) throw invalid();
        if (!node.path("trackedDiffHash").isNull() && !node.path("trackedDiffHash").asText().matches("[a-f0-9]{64}")) throw invalid();
        if (!node.path("codeStatus").asText().equals("UNKNOWN") && (node.path("codeSha").isNull() || node.path("trackedDiffHash").isNull())) throw invalid();
        for (String key : Set.of("promptVersion", "policyVersion", "provider", "model", "adapterVersion")) {
            if (!node.path(key).isTextual()) throw invalid();
        }
        node.put("datasetVersion", safe(data.datasetVersion())).put("datasetHash", data.sha256())
                .put("fixtureVersion", safe(data.fixtureVersion())).put("contextWindow", variant.contextWindow());
        return node;
    }
    private static String safe(String text) {
        if (text != null && !text.matches("[A-Za-z0-9_.:/-]{1,128}")) throw invalid();
        return text;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_EVALUATION_REPORT"); }
}
