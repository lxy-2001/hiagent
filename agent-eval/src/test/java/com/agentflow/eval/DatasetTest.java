package com.agentflow.eval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DatasetTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode document() throws Exception {
        return (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/evaluation/dataset-v1.json"));
    }
    private EvalDataset load(ObjectNode node) {
        return EvalDataset.load(mapper.writeValueAsBytes(node));
    }
    @Test void completeDatasetIsImmutableAndHasStableContentHash() throws Exception {
        var data = load(document());
        assertEquals(26, data.cases().size());
        assertEquals("C01", data.cases().get(0).id());
        assertEquals(data.sha256(), load(document()).sha256());
        assertThrows(UnsupportedOperationException.class, () -> data.cases().clear());
        assertEquals(16384, EvalVariant.baseline().contextWindow());
        assertEquals(4096, EvalVariant.compactContext().contextWindow());
    }
    @Test void rejectsUnknownFieldsAtEachLevel() throws Exception {
        for (String pointer : new String[]{"", "/cases/0", "/cases/0/budget", "/cases/0/expectations"}) {
            var node = document(); ((ObjectNode) node.at(pointer)).put("unexpected", true);
            assertThrows(IllegalArgumentException.class, () -> load(node));
        }
    }
    @Test void rejectsInvalidIdentityFixtureBudgetAndExpectation() throws Exception {
        for (String[] mutation : new String[][]{{"id", "C02"}, {"fixtureId", "arbitrary-class"}, {"driver", "REMOTE"}}) {
            var node = document(); ((ObjectNode) node.at("/cases/0")).put(mutation[0], mutation[1]);
            assertThrows(IllegalArgumentException.class, () -> load(node));
        }
        var budget = document(); ((ObjectNode) budget.at("/cases/0/budget")).put("maxIterations", 17);
        assertThrows(IllegalArgumentException.class, () -> load(budget));
        var reason = document(); ((ObjectNode) reason.at("/cases/0/expectations")).put("terminationReason", "FAKE_SUCCESS");
        assertThrows(IllegalArgumentException.class, () -> load(reason));
    }
    @Test void rejectsMissingCaseDuplicateRulesAndOversizedInput() throws Exception {
        var missing = document(); ((tools.jackson.databind.node.ArrayNode) missing.path("cases")).remove(0);
        assertThrows(IllegalArgumentException.class, () -> load(missing));
        var rules = document(); ((tools.jackson.databind.node.ArrayNode) rules.at("/cases/0/expectations/taskRules")).add("TERMINAL");
        assertThrows(IllegalArgumentException.class, () -> load(rules));
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.load(" ".repeat(1048577).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void duplicateJsonKeysAreRejected() throws Exception {
        String json = mapper.writeValueAsString(document()).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.load(json.getBytes(StandardCharsets.UTF_8)));
    }
}
