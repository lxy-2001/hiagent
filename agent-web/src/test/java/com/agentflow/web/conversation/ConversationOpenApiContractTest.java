package com.agentflow.web.conversation;

import com.agentflow.web.memory.ConfirmedMemoryController;
import com.agentflow.web.memory.ConfirmedMemoryService;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConversationOpenApiContractTest {
    @Test void existingRunContractIncludesContextStepsAndBothNewTerminationReasons() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/run-lifecycle.yaml")) {
            Map<String, Object> contract = new Yaml().load(input);
            Map<String, Map<String, Object>> schemas = (Map) ((Map) contract.get("components")).get("schemas");
            assertThat((List<String>) schemas.get("TerminationReason").get("enum"))
                    .contains("CONTEXT_BUDGET_EXCEEDED", "CONTEXT_SOURCE_UNAVAILABLE");
            assertThat((Map) schemas.get("CreateTaskRequest").get("properties")).containsKey("sessionId");
            var reasons = maps(contract).filter(map -> map.containsKey("runtimeReason"))
                    .flatMap(map -> maps(map.get("runtimeReason")))
                    .filter(map -> map.containsKey("enum")).toList();
            assertThat(reasons).hasSize(2);
            reasons.forEach(reason -> assertThat((List<String>) reason.get("enum")).contains("CONTEXT_BUDGET_EXCEEDED"));
        }
    }
    private static java.util.stream.Stream<Map<?, ?>> maps(Object node) {
        if (node instanceof Map<?, ?> map) return java.util.stream.Stream.concat(java.util.stream.Stream.of(map), map.values().stream().flatMap(ConversationOpenApiContractTest::maps));
        if (node instanceof List<?> list) return list.stream().flatMap(ConversationOpenApiContractTest::maps);
        return java.util.stream.Stream.empty();
    }
    @Test void trackedContractHasSevenOperationsAndStringVersionsMatchingDtos() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/feature004-openapi.yaml")) {
            assertThat(input).isNotNull();
            Map<String, Object> contract = new Yaml().load(input);
            Map<String, Map<String, Object>> paths = (Map) contract.get("paths");
            assertThat(paths).hasSize(6);
            long operations = paths.values().stream().flatMap(path -> path.keySet().stream())
                    .filter(List.of("get", "post", "put", "delete")::contains).count();
            assertThat(operations).isEqualTo(7);
            Map<String, Map<String, Object>> schemas = (Map) ((Map) contract.get("components")).get("schemas");
            Map<String, Object> request = (Map) schemas.get("CreateTaskRequest").get("properties");
            assertThat((Map) request.get("input")).containsEntry("maxLength", 8000);
            assertThat(request).containsKey("sessionId");
            var mapper = new ObjectMapper();
            var slot = new ConfirmedMemoryService.Slot("s", "project_stack", "PROJECT_FACT", "DELETED", null, "9007199254740993", null, null);
            assertThat(mapper.readTree(mapper.writeValueAsString(slot)).get("version").asText()).isEqualTo("9007199254740993");
            assertThat(ConversationController.class.getDeclaredMethods()).filteredOn(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)).hasSize(3);
            assertThat(ConfirmedMemoryController.class.getDeclaredMethods()).filteredOn(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)).hasSize(1);
        }
    }
}
