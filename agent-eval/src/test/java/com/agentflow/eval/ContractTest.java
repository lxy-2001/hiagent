package com.agentflow.eval;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @Test void documentedExamplesMatchVersionedContracts() throws Exception {
        for (String[] pair : new String[][]{{"dataset", "dataset-v1"}, {"report", "report-example"},
                {"price", "price-example"}, {"comparison", "comparison-example"}}) {
            try (var schema = getClass().getResourceAsStream("/evaluation/" + pair[0] + ".schema.json");
                 var example = getClass().getResourceAsStream("/evaluation/" + pair[1] + ".json")) {
                assertNotNull(schema);
                assertNotNull(example);
                assertTrue(registry.getSchema(schema).validate(mapper.readTree(example)).isEmpty(), pair[0]);
            }
        }
    }

    @Test void corruptedReportsAndUnsupportedVersionsAreRejected() throws Exception {
        try (var stream = getClass().getResourceAsStream("/evaluation/report.schema.json")) {
            var schema = registry.getSchema(stream);
            var examples = mapper.readTree(getClass().getResourceAsStream("/evaluation/report-invalid.json"));
            for (var example : examples) assertFalse(schema.validate(example.path("document")).isEmpty());
            ObjectNode valid = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/evaluation/report-example.json"));
            valid.put("schemaVersion", 2);
            assertFalse(schema.validate(valid).isEmpty());
        }
    }
}
