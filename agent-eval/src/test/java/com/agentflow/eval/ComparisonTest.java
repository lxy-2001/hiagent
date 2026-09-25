package com.agentflow.eval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonTest {
    private ObjectNode report() throws Exception {
        var root = (ObjectNode) EvalJson.MAPPER.readTree(getClass().getResourceAsStream("/evaluation/report-example.json"));
        ((ObjectNode) root.path("provenance")).put("codeStatus", "KNOWN").put("codeSha", "a".repeat(40)).put("trackedDiffHash", "b".repeat(64));
        var example = root.path("cases").get(0).deepCopy();
        var cases = root.putArray("cases");
        for (int i = 1; i <= 26; i++) { var item = (ObjectNode) example.deepCopy(); item.put("caseId", "C%02d".formatted(i)); cases.add(item); }
        ((ObjectNode) root.path("summary")).put("total", 26).put("passed", 26).put("hardPassed", 26).put("hardApplicable", 26);
        return root;
    }
    private void changedWindow(ObjectNode candidate) {
        ((ObjectNode) candidate.path("provenance")).put("contextWindow", 4096).put("configHash", "a".repeat(64));
    }
    @Test void labelsAloneDoNotConstituteAnExperiment() throws Exception {
        var a = report(); var b = report(); b.put("variant", "other");
        assertEquals("NO_EFFECTIVE_CHANGE", new ReportComparator().compare(a, b, "contextWindow").path("status").asText());
    }
    @Test void detectsRegressionInASingleChangedDimension() throws Exception {
        var a = report(); var b = report(); changedWindow(b);
        ((ObjectNode) b.path("cases").get(25)).put("caseStatus", "FAIL");
        ((ObjectNode) b.path("summary")).put("passed", 25).put("failed", 1).put("taskSuccessRate", 25.0 / 26);
        ((ObjectNode) b.path("cases").get(25).path("assertions").get(0)).put("status", "FAIL");
        ((ObjectNode) b.path("summary")).put("hardPassed", 25);
        b.put("gateStatus", "FAIL");
        var comparison = new ReportComparator().compare(a, b, "contextWindow");
        assertEquals("REGRESSION", comparison.path("status").asText());
        assertEquals("REGRESSION", comparison.path("pairs").get(25).path("change").asText());
    }
    @Test void rejectsMissingPairsModeDataAndAdditionalEffectiveChanges() throws Exception {
        for (String field : new String[]{"datasetHash", "fixtureHash", "assertionHash", "fixedConfigHash", "promptHash"}) {
            var a = report(); var b = report(); changedWindow(b);
            ((ObjectNode) b.path("provenance")).put(field, "b".repeat(64));
            assertEquals("INCOMPARABLE", new ReportComparator().compare(a, b, "contextWindow").path("status").asText(), field);
        }
        var a = report(); var b = report(); ((tools.jackson.databind.node.ArrayNode) b.path("cases")).remove(0);
        assertEquals("INCOMPARABLE", new ReportComparator().compare(a, b, "contextWindow").path("status").asText());
        b = report(); b.put("mode", "MODEL_LIVE");
        assertEquals("INCOMPARABLE", new ReportComparator().compare(a, b, "contextWindow").path("status").asText());
    }
    @Test void rejectsInventedSummaryUnknownCodeAndInconsistentConfigHashes() throws Exception {
        var a = report(); var b = report(); changedWindow(b);
        ((ObjectNode) b.path("summary")).put("hardPassed", 0);
        assertEquals("INCOMPARABLE", new ReportComparator().compare(a, b, "contextWindow").path("status").asText());
        b = report(); ((ObjectNode) b.path("provenance")).put("codeStatus", "UNKNOWN").putNull("codeSha");
        assertEquals("INCOMPARABLE", new ReportComparator().compare(b, b, "contextWindow").path("status").asText());
        b = report(); ((ObjectNode) b.path("provenance")).put("contextWindow", 4096);
        assertEquals("INCOMPARABLE", new ReportComparator().compare(a, b, "contextWindow").path("status").asText());
    }
    @Test void promptLabelsWithoutDifferentPromptContentAreNotAnEffectiveChange() throws Exception {
        var a = report(); var b = report(); ((ObjectNode) b.path("provenance")).put("promptVersion", "renamed");
        assertEquals("NO_EFFECTIVE_CHANGE", new ReportComparator().compare(a, b, "prompt").path("status").asText());
    }
    @Test void costDeltasRequireKnownAmountsInTheSameCurrency() throws Exception {
        var a = report(); var b = report(); changedWindow(b);
        for (var report : new ObjectNode[]{a, b}) {
            var cost = (ObjectNode) report.path("cases").get(0).path("modelCostEstimate");
            cost.put("status", "ESTIMATED").put("currency", "USD").put("priceId", "synthetic").put("amount", report == a ? "1.00000000" : "2.00000000");
        }
        var comparison = new ReportComparator().compare(a, b, "contextWindow");
        boolean found = false;
        for (var delta : comparison.path("pairs").get(0).path("metricDeltas")) {
            if (delta.path("name").asText().equals("modelCostEstimate")) { found = true; assertEquals(1.0, delta.path("delta").asDouble()); }
        }
        assertTrue(found);
        ((ObjectNode) b.path("cases").get(0).path("modelCostEstimate")).put("currency", "CNY");
        for (var delta : new ReportComparator().compare(a, b, "contextWindow").path("pairs").get(0).path("metricDeltas")) {
            assertNotEquals("modelCostEstimate", delta.path("name").asText());
        }
    }
    @Test void neverSubtractsDifferentTimingScopes() throws Exception {
        var a = report(); var b = report(); changedWindow(b);
        ((ObjectNode) b.path("cases").get(0).path("metrics").path("runtimeTotal")).put("scope", "WEB_ACTIVE");
        var deltas = new ReportComparator().compare(a, b, "contextWindow").path("pairs").get(0).path("metricDeltas");
        for (var delta : deltas) assertNotEquals("runtimeTotal", delta.path("name").asText());
    }
}
