package com.agentflow.demo.approval;

import com.networknt.schema.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class Feature006EvidenceTest {
    @Test void exportsScenarioEvidenceFromActualSurefireReportsWithoutInventingMissingRuns() throws Exception {
        JsonNode cases;
        try(var input=getClass().getResourceAsStream("/feature006/scenarios.json")) { cases=new ObjectMapper().readTree(input); }
        var report=new java.util.ArrayList<Map<String,Object>>();
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        for(var scenario:cases) {
            String module=scenario.path("module").asString(); String suite=scenario.path("suite").asString();
            Path path=Path.of("..",module,"target","surefire-reports","TEST-"+suite+".xml");
            String status="NOT_RUN"; int tests=0;
            if(Files.exists(path)) {
                var root=factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
                tests=Integer.parseInt(root.getAttribute("tests"));
                status=tests>0 && Integer.parseInt(root.getAttribute("errors"))==0 && Integer.parseInt(root.getAttribute("failures"))==0 ? "PASS" : "FAIL";
            }
            report.add(Map.of("caseId",scenario.path("caseId").asString(),"mode","OFFLINE_FIXTURE","suite",suite,
                    "status",status,"suiteTests",tests,"source",path.toString()));
        }
        assertThat(report).hasSizeGreaterThanOrEqualTo(12);
        Path target=Path.of("target/feature006"); Files.createDirectories(target);
        String sha=git("rev-parse","HEAD"); String dirty=git("status","--porcelain");
        Files.writeString(target.resolve("scenarios.json"),new ObjectMapper().writeValueAsString(Map.of(
                "commit",sha,"workingTreeDirty",!dirty.isBlank(),"protocol","2025-11-25","sdk","1.1.4",
                "cases",report,"realModels","NOT_RUN","thirdPartyMcp","NOT_RUN")));
    }
    private static String git(String... args) throws Exception {
        var command=new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(args));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        if(process.waitFor()!=0) return "UNAVAILABLE";
        return output;
    }

    private final ObjectMapper json = new ObjectMapper();
    @Test void everyPositiveAndNegativeInvocationExampleUsesTheSameFormatAwareSchema() throws Exception {
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema schema;
        try (var stream = getClass().getResourceAsStream("/feature006/tool-invocation.schema.json")) { schema = registry.getSchema(stream); }
        JsonNode examples;
        try (var stream = getClass().getResourceAsStream("/feature006/examples.json")) { examples = json.readTree(stream); }
        int positives=0, negatives=0;
        for (var entry : examples.properties()) {
            if (!entry.getKey().startsWith("invocation") || entry.getKey().equals("invocationInvalidCases")) continue;
            assertThat(schema.validate(entry.getValue(), context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))))
                    .as(entry.getKey()).isEmpty(); positives++;
        }
        for (var invalid : examples.path("invocationInvalidCases")) {
            ObjectNode candidate = (ObjectNode) examples.path(invalid.path("base").asString()).deepCopy();
            for (var entry : invalid.path("changes").properties()) candidate.set(entry.getKey(),entry.getValue());
            for (var field : invalid.path("remove")) candidate.remove(field.asString());
            assertThat(schema.validate(candidate, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))))
                    .as(invalid.path("name").asString()).isNotEmpty(); negatives++;
        }
        assertThat(positives).isGreaterThanOrEqualTo(5); assertThat(negatives).isGreaterThanOrEqualTo(12);
        Path target=Path.of("target/feature006"); Files.createDirectories(target);
        Files.writeString(target.resolve("schema-validation.json"),json.writeValueAsString(Map.of(
                "schemaVersion","006-v1","positiveCases",positives,"negativeCases",negatives,"formatAssertions",true,
                "status","PASS","realModels","NOT_RUN","thirdPartyMcp","NOT_RUN")));
    }
}
