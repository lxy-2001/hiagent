package com.agentflow.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Read-only paired comparison. Incompatible inputs never produce an aggregate improvement claim. */
public final class ReportComparator {
    private static final Map<String, Set<String>> DIMENSIONS = Map.of(
            "code", Set.of("codeSha", "trackedDiffHash", "workingTreeDirty", "untrackedCount", "codeStatus"),
            "prompt", Set.of("promptVersion", "promptHash"), "policy", Set.of("policyVersion", "policyHash"),
            "model", Set.of("provider", "model", "adapterVersion"), "contextWindow", Set.of("contextWindow"));

    public JsonNode compare(JsonNode baseline, JsonNode candidate, String dimension) {
        if (!DIMENSIONS.containsKey(dimension)) throw new IllegalArgumentException("UNKNOWN_COMPARISON_DIMENSION");
        ObjectNode output = EvalJson.MAPPER.createObjectNode();
        output.put("schemaVersion", 1).put("baselineId", safeId(baseline.path("evaluationId").asText()))
                .put("candidateId", safeId(candidate.path("evaluationId").asText()))
                .put("baselineHash", EvalJson.hash(baseline)).put("candidateHash", EvalJson.hash(candidate)).put("dimension", dimension);
        var reasons = output.putArray("reasons"); var pairs = output.putArray("pairs");
        Map<String, JsonNode> left, right;
        try { left = cases(baseline); right = cases(candidate); }
        catch (IllegalArgumentException e) { output.put("status", "INCOMPARABLE"); reasons.add("INVALID_OR_INCOMPLETE_REPORT"); return output; }
        if (!baseline.path("mode").equals(candidate.path("mode")) || !baseline.path("repeat").equals(candidate.path("repeat"))) reasons.add("MODE_OR_REPEAT_MISMATCH");
        if (!left.keySet().equals(right.keySet())) reasons.add("CASE_SET_MISMATCH");
        var a = baseline.path("provenance"); var b = candidate.path("provenance");
        var allowed = DIMENSIONS.get(dimension);
        boolean changed = false;
        for (var field : a.properties()) {
            String key = field.getKey(); var other = b.path(key);
            if (key.equals("configHash")) continue;
            if (allowed.contains(key)) { if (!key.equals("promptVersion") && !key.equals("policyVersion")) changed |= !field.getValue().equals(other); }
            else if (!field.getValue().equals(other)) reasons.add("PROVENANCE_MISMATCH_" + key.toUpperCase(Locale.ROOT));
        }
        if (!a.propertyNames().equals(b.propertyNames())) reasons.add("PROVENANCE_FIELDS_MISMATCH");
        for (String key : List.of("datasetHash", "fixtureHash", "assertionHash", "fixedConfigHash", "promptHash", "policyHash", "configHash")) {
            if (!a.path(key).asText().matches("[a-f0-9]{64}") || !b.path(key).asText().matches("[a-f0-9]{64}")) reasons.add("REQUIRED_HASH_MISSING");
        }
        if ((!Set.of("KNOWN", "COPIED_SOURCE").contains(a.path("codeStatus").asText())
                || !Set.of("KNOWN", "COPIED_SOURCE").contains(b.path("codeStatus").asText()))) reasons.add("CODE_PROVENANCE_UNKNOWN");
        if (changed && !dimension.equals("code") && a.path("configHash").equals(b.path("configHash"))) reasons.add("CONFIG_HASH_UNCHANGED");
        if (!changed && !a.path("configHash").equals(b.path("configHash"))) reasons.add("UNDECLARED_CONFIG_CHANGE");
        boolean regression = false;
        for (String key : left.keySet()) {
            var l = left.get(key); var r = right.get(key);
            if (r == null) continue;
            if (!l.path("driver").equals(r.path("driver"))) reasons.add("DRIVER_MISMATCH");
            String ls = l.path("caseStatus").asText(), rs = r.path("caseStatus").asText();
            String change = ls.equals("PASS") && !rs.equals("PASS") ? "REGRESSION"
                    : !ls.equals("PASS") && rs.equals("PASS") ? "IMPROVEMENT" : "UNCHANGED";
            regression |= change.equals("REGRESSION");
            var pair = pairs.addObject().put("caseId", l.path("caseId").asText()).put("repeat", l.path("repeat").asInt())
                    .put("baselineStatus", ls).put("candidateStatus", rs).put("change", change);
            var deltas = pair.putArray("metricDeltas");
            for (var field : l.path("metrics").properties()) {
                var lm = field.getValue(); var rm = r.path("metrics").path(field.getKey());
                if (lm.path("status").asText().equals("KNOWN") && rm.path("status").asText().equals("KNOWN")
                        && lm.path("unit").equals(rm.path("unit")) && lm.path("scope").equals(rm.path("scope"))
                        && lm.path("value").isNumber() && rm.path("value").isNumber()) {
                    double delta = rm.path("value").asDouble() - lm.path("value").asDouble();
                    if (Double.isFinite(delta)) deltas.addObject().put("name", safeId(field.getKey())).put("scope", safeId(lm.path("scope").asText()))
                            .put("unit", safeId(lm.path("unit").asText())).put("delta", delta);
                }
            }
        }
        for (var pair : pairs) {
            String key = pair.path("caseId").asText() + "/" + pair.path("repeat").asInt();
            var lc = left.get(key).path("modelCostEstimate"); var rc = right.get(key).path("modelCostEstimate");
            if (lc.path("status").asText().equals("ESTIMATED") && rc.path("status").asText().equals("ESTIMATED")
                    && lc.path("currency").asText().matches("[A-Z]{3}") && lc.path("currency").equals(rc.path("currency"))
                    && lc.path("amount").asText().matches("[0-9]{1,40}\\.[0-9]{8}")
                    && rc.path("amount").asText().matches("[0-9]{1,40}\\.[0-9]{8}")) {
                var delta = new java.math.BigDecimal(rc.path("amount").asText()).subtract(new java.math.BigDecimal(lc.path("amount").asText()));
                ((tools.jackson.databind.node.ArrayNode) pair.path("metricDeltas")).addObject().put("name", "modelCostEstimate")
                        .put("scope", "MODEL_COST").put("unit", lc.path("currency").asText()).put("delta", delta);
            }
        }
        if (!reasons.isEmpty()) {
            output.put("status", "INCOMPARABLE");
            for (var pair : pairs) { ((ObjectNode) pair).put("change", "INCOMPARABLE"); ((ObjectNode) pair).putArray("metricDeltas"); }
        } else output.put("status", !changed ? "NO_EFFECTIVE_CHANGE" : regression ? "REGRESSION" : "PASS");
        return output;
    }

    private static Map<String, JsonNode> cases(JsonNode report) {
        if (!report.isObject() || !report.path("schemaVersion").isIntegralNumber() || report.path("schemaVersion").asInt() != 1
                || !report.path("cases").isArray() || !report.path("provenance").isObject()) throw invalid();
        int repeat = report.path("repeat").asInt();
        boolean live = report.path("mode").asText().equals("MODEL_LIVE");
        if (repeat < 1 || repeat > (live ? 3 : 10) || !Set.of("MODEL_LIVE", "OFFLINE_FIXTURE").contains(report.path("mode").asText())) throw invalid();
        int count = (live ? 2 : 26) * repeat;
        if (report.path("cases").size() != count || report.path("summary").path("total").asInt() != count) throw invalid();
        var result = new TreeMap<String, JsonNode>();
        var counts = new HashMap<String, Integer>();
        for (var c : report.path("cases")) {
            String id = c.path("caseId").asText(); int n = c.path("repeat").asInt();
            if (!id.matches("C[0-9]{2}") || n < 1 || n > repeat || !Set.of("CORE", "HTTP").contains(c.path("driver").asText())) throw invalid();
            String status = c.path("caseStatus").asText(); CaseReport.Status.valueOf(status);
            counts.merge(status, 1, Integer::sum);
            if (result.put(id + "/" + n, c) != null) throw invalid();
        }
        for (int i = 1; i <= (live ? 2 : 26); i++) for (int n = 1; n <= repeat; n++) if (!result.containsKey("C%02d/%d".formatted(i, n))) throw invalid();
        String[] names = {"passed", "failed", "error", "incomplete", "skipped", "notRun"}; int i = 0;
        for (var status : CaseReport.Status.values()) if (report.path("summary").path(names[i++]).asInt(-1) != counts.getOrDefault(status.name(), 0)) throw invalid();
        int hardPassed = 0, hardApplicable = 0, hardIncomplete = 0;
        for (var c : result.values()) {
            if (!c.path("assertions").isArray()) throw invalid();
            var ids = new HashSet<String>(); boolean failed = false, incomplete = false;
            for (var assertion : c.path("assertions")) {
                EvalCase.Rule.valueOf(assertion.path("id").asText());
                if (!ids.add(assertion.path("id").asText()) || !assertion.path("hard").isBoolean()) throw invalid();
                var status = AssertionResult.Status.valueOf(assertion.path("status").asText());
                failed |= status == AssertionResult.Status.FAIL; incomplete |= status == AssertionResult.Status.INCOMPLETE;
                if (assertion.path("hard").asBoolean()) {
                    if (status != AssertionResult.Status.NOT_APPLICABLE) hardApplicable++;
                    if (status == AssertionResult.Status.PASS) hardPassed++;
                    if (status == AssertionResult.Status.INCOMPLETE) hardIncomplete++;
                }
            }
            String status = c.path("caseStatus").asText();
            if (status.equals("PASS") && (failed || incomplete || ids.isEmpty() || (!c.path("recordingComplete").asBoolean() && !("C23".equals(c.path("caseId").asText())
                    && "HTTP".equals(c.path("driver").asText()) && "CONTEXT_SOURCE_UNAVAILABLE".equals(c.path("terminationReason").asText())
                    && c.path("modelAttempts").asInt(-1) == 0 && c.path("steps").isEmpty() && c.path("invocations").isEmpty())))) throw invalid();
            if (status.equals("FAIL") && !failed || status.equals("INCOMPLETE") && (!incomplete || failed)) throw invalid();
        }
        var summary = report.path("summary");
        if (summary.path("hardPassed").asInt(-1) != hardPassed || summary.path("hardApplicable").asInt(-1) != hardApplicable
                || summary.path("hardIncomplete").asInt(-1) != hardIncomplete) throw invalid();
        boolean passed = counts.getOrDefault("PASS", 0) == count && hardPassed == hardApplicable;
        if (!report.path("gateStatus").asText().equals(passed ? "PASS" : "FAIL")) throw invalid();
        double rate = counts.getOrDefault("PASS", 0) / (double) count;
        if (!summary.path("taskSuccessRate").isNumber() || Math.abs(summary.path("taskSuccessRate").asDouble() - rate) > 1e-12) throw invalid();
        return result;
    }
    private static String safeId(String value) { return value.matches("[A-Za-z0-9_.:/-]{1,128}") ? value : "invalid"; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_COMPARISON_INPUT"); }
}
