package com.agentflow.demo.evaluation;

import com.agentflow.core.context.ContextPolicy;
import com.agentflow.eval.*;
import com.networknt.schema.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Test command boundary: explicit configuration, local provenance and closed report schema. */
final class EvaluationEntry {
    static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    record Options(EvalVariant variant, int repeat, String mode, boolean allowPaid) { }
    static Options options(Properties properties) {
        String variant = properties.getProperty("agentflow.eval.variant", "baseline");
        var selected = switch (variant) {
            case "baseline" -> EvalVariant.baseline(); case "compact-context" -> EvalVariant.compactContext();
            default -> throw new IllegalArgumentException("INVALID_VARIANT");
        };
        int repeat = Integer.parseInt(properties.getProperty("agentflow.eval.repeat", "1"));
        String mode = properties.getProperty("agentflow.eval.mode", "offline");
        if (!Set.of("offline", "live").contains(mode) || repeat < 1 || repeat > 10 || mode.equals("live") && repeat > 3) throw new IllegalArgumentException("INVALID_EVALUATION_CONFIGURATION");
        boolean allow = properties.getProperty("agentflow.eval.allow-paid", "false").equals("true");
        if (mode.equals("live") && !allow) throw new IllegalArgumentException("PAID_MODEL_NOT_AUTHORIZED");
        return new Options(selected, repeat, mode, allow);
    }
    static EvalDataset dataset() throws Exception {
        return EvalDataset.load(resource("/evaluation/dataset-v1.json"));
    }
    static RunReport run(Options options) throws Exception {
        if (!options.mode().equals("offline")) throw new IllegalArgumentException("LIVE_DRIVER_NOT_AVAILABLE");
        long suiteStart = System.nanoTime();
        HttpScenarioDriver.prepareInfrastructure();
        var data = dataset();
        var metadata = metadata(options.variant());
        var runner = new EvaluationRunner(Map.of(EvalCase.Driver.CORE, () -> {
            try { return new CoreScenarioDriver(); } catch (java.io.IOException e) { throw new IllegalStateException("FIXTURE_UNAVAILABLE"); }
        }, EvalCase.Driver.HTTP, HttpScenarioDriver::new), java.time.Duration.ofMinutes(10).minusNanos(System.nanoTime() - suiteStart));
        var executions = runner.run(data, options.variant(), options.repeat());
        return RunReport.create(data, options.variant(), options.repeat(), "OFFLINE_FIXTURE", metadata, executions);
    }
    static Map<String, Object> metadata(EvalVariant variant) throws Exception {
        Path root = root();
        String manifest = System.getProperty("agentflow.eval.provenance");
        var code = CodeProvenance.read(root, manifest == null ? null : Path.of(manifest), List.of("pom.xml",
                "agent-demo/src/test/java/com/agentflow/demo/evaluation/CoreScenarioDriver.java",
                "agent-demo/src/test/java/com/agentflow/demo/evaluation/HttpScenarioDriver.java"));
        var values = new HashMap<String, Object>();
        values.put("codeStatus", code.status()); values.put("codeSha", code.codeSha());
        values.put("workingTreeDirty", code.workingTreeDirty()); values.put("trackedDiffHash", code.trackedDiffHash()); values.put("untrackedCount", code.untrackedCount());
        // Include executable fixture source, so changing scripts cannot preserve their identity accidentally.
        var fixture = new StringBuilder(new String(resource("/evaluation/fixtures-v1.json"), StandardCharsets.UTF_8));
        for (String file : List.of("CoreScenarioDriver.java", "HttpScenarioDriver.java", "EvaluationFixtureApplication.java", "FixedEvidenceRetriever.java", "MissingUsageModel.java"))
            fixture.append(Files.readString(root.resolve("agent-demo/src/test/java/com/agentflow/demo/evaluation/" + file)));
        fixture.append(Files.readString(root.resolve("agent-demo/src/test/java/com/agentflow/demo/approval/OfflineMcpFixture.java")));
        values.put("fixtureHash", hash(fixture.toString()));
        values.put("assertionHash", hash(Files.readString(root.resolve("agent-eval/src/main/java/com/agentflow/eval/EvaluationScorer.java")) + new String(resource("/evaluation/dataset-v1.json"), StandardCharsets.UTF_8)));
        values.put("promptVersion", ContextPolicy.defaults().promptVersion()); values.put("promptHash", hash(ContextPolicy.DEFAULT_SYSTEM));
        String policy = new String(resource("/application-fixture.yml"), StandardCharsets.UTF_8);
        values.put("policyVersion", "eval-policy-v1"); values.put("policyHash", hash(policy));
        values.put("provider", "fixture"); values.put("model", "deterministic-v1"); values.put("adapterVersion", "fixture-v1");
        String fixed = values.get("fixtureHash") + ":" + values.get("assertionHash") + ":" + values.get("promptHash") + ":" + values.get("policyHash");
        values.put("fixedConfigHash", hash(fixed)); values.put("configHash", hash(fixed + ":window=" + variant.contextWindow()));
        return values;
    }
    static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("agent-demo/pom.xml"))) path = path.getParent();
        if (path == null) throw new IllegalArgumentException("SOURCE_ROOT_UNAVAILABLE");
        return path;
    }
    static byte[] resource(String name) throws Exception {
        try (var input = EvaluationEntry.class.getResourceAsStream(name)) { return Objects.requireNonNull(input).readAllBytes(); }
    }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static void validateReport(JsonNode report) throws Exception {
        try (var stream = EvaluationEntry.class.getResourceAsStream("/evaluation/report.schema.json")) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
            var errors = schema.validate(report);
            if (!errors.isEmpty()) throw new IllegalArgumentException("INVALID_REPORT_SCHEMA " + errors);
        }
    }
    static JsonNode readReport(Path path) throws Exception {
        if (Files.size(path) > 10 * 1024 * 1024) throw new IllegalArgumentException("REPORT_TOO_LARGE");
        var report = JSON.readTree(Files.readAllBytes(path)); validateReport(report); return report;
    }
}
