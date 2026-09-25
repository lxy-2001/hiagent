package com.agentflow.eval;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportTest {
    @TempDir Path directory;
    private EvalDataset dataset() throws Exception {
        return EvalDataset.load(getClass().getResourceAsStream("/evaluation/dataset-v1.json").readAllBytes());
    }
    private Map<String, Object> metadata() {
        var values = new LinkedHashMap<String, Object>();
        values.put("codeSha", null); values.put("codeStatus", "UNKNOWN"); values.put("workingTreeDirty", false);
        values.put("trackedDiffHash", null); values.put("untrackedCount", 0);
        for (String key : List.of("fixtureHash", "assertionHash", "promptHash", "policyHash", "configHash", "fixedConfigHash")) values.put(key, "0".repeat(64));
        for (String key : List.of("promptVersion", "policyVersion", "provider", "model", "adapterVersion")) values.put(key, "test");
        return values;
    }
    @Test void omittedCasesRemainVisibleAndFailTheGate() throws Exception {
        var report = RunReport.create(dataset(), EvalVariant.baseline(), 1, "OFFLINE_FIXTURE", metadata(), List.of());
        var json = report.document();
        assertEquals("FAIL", json.path("gateStatus").asText());
        assertEquals(26, json.path("summary").path("notRun").asInt());
        assertEquals(26, json.path("cases").size());
        try (var stream = getClass().getResourceAsStream("/evaluation/report.schema.json")) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
            assertTrue(schema.validate(json).isEmpty(), () -> schema.validate(json).toString());
        }
    }
    @Test void writesBothReportsBeforeCallerChecksTheGate() throws Exception {
        var report = RunReport.create(dataset(), EvalVariant.baseline(), 1, "OFFLINE_FIXTURE", metadata(), List.of());
        Path output = new ReportWriter().write(directory, report);
        assertTrue(Files.isRegularFile(output.resolve("report.json")));
        assertTrue(Files.isRegularFile(output.resolve("report.md")));
        assertFalse(report.passed());
        assertThrows(java.io.IOException.class, () -> new ReportWriter().write(directory, report));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void rawAnswerAndFactValuesNeverAppearInEitherArtifact() throws Exception {
        String sentinel = "PRIVATE_SENTINEL_007";
        var step = com.agentflow.core.AgentStepRecord.success("run", 1, com.agentflow.core.AgentStepType.FINAL,
                null, sentinel, sentinel, 0, 2, 1, "decision", null, true);
        var result = com.agentflow.core.AgentResult.success("run", sentinel, List.of(step), new com.agentflow.core.chat.TokenUsage(2, 1, 3));
        var event = com.agentflow.core.AgentEvent.traced("run", com.agentflow.core.AgentStepType.FINAL, "final", sentinel, 1, null, true);
        var attempt = new EvaluationMetrics.Attempt(result.usage(), com.agentflow.core.model.UsageSource.REPORTED, 0);
        var observed = new ObservedCase(result, List.of(event), List.of(), List.of(attempt), true, 0,
                Map.of(EvalCase.Rule.ANSWER_MARKER, new ObservedCase.Fact(sentinel, "OK"),
                        EvalCase.Rule.NO_SECRET, new ObservedCase.Fact(List.of(), List.of())), null, null);
        var data = dataset(); var score = new EvaluationScorer().score(data.cases().get(0), observed);
        var price = new PriceSnapshot("synthetic", "test", "test", "USD", java.time.LocalDate.of(2026, 9, 25),
                "test", java.math.BigDecimal.ONE, java.math.BigDecimal.ONE);
        var execution = new RunReport.Execution("C01", 1, observed, score, Map.of(), List.of());
        var report = RunReport.create(data, EvalVariant.baseline(), 1, "OFFLINE_FIXTURE", metadata(), List.of(execution), price);
        assertEquals("0.00000300", report.document().path("cases").get(0).path("modelCostEstimate").path("amount").asText());
        var output = new ReportWriter().write(directory, report);
        assertFalse(Files.readString(output.resolve("report.json")).contains(sentinel));
        assertFalse(Files.readString(output.resolve("report.md")).contains(sentinel));
        try (var stream = getClass().getResourceAsStream("/evaluation/report.schema.json")) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
            assertTrue(schema.validate(report.document()).isEmpty(), () -> schema.validate(report.document()).toString());
        }
    }
    @Test void invalidProvenanceCannotMasqueradeAsKnown() throws Exception {
        var data = dataset();
        for (String key : List.of("codeSha", "trackedDiffHash", "fixtureHash")) {
            var invalid = metadata(); invalid.put(key, "fake");
            assertThrows(IllegalArgumentException.class, () -> RunReport.create(data, EvalVariant.baseline(), 1, "OFFLINE_FIXTURE", invalid, List.of()));
        }
        var known = metadata(); known.put("codeStatus", "KNOWN");
        assertThrows(IllegalArgumentException.class, () -> RunReport.create(data, EvalVariant.baseline(), 1, "OFFLINE_FIXTURE", known, List.of()));
    }
    @Test void unknownMetadataAndMarkdownPayloadsAreRejected() throws Exception {
        var metadata = metadata(); metadata.put("apiKey", "SECRET_SENTINEL");
        var data = dataset();
        assertThrows(IllegalArgumentException.class, () -> RunReport.create(data, EvalVariant.baseline(), 1, "OFFLINE_FIXTURE", metadata, List.of()));
        var injection = metadata(); injection.put("model", "<script>SECRET_SENTINEL</script>");
        assertThrows(IllegalArgumentException.class, () -> RunReport.create(data, EvalVariant.baseline(), 1, "OFFLINE_FIXTURE", injection, List.of()));
    }
}
