package com.agentflow.web.conversation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class WorkbenchOpenApiContractTest {
    @Test void additiveResponseAndHistoryFieldsMatchTheVersionedContract() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/feature009-openapi.yaml")) {
            assertThat(input).isNotNull();
            var contract = new ObjectMapper().readTree(input);
            assertThat(contract.path("paths").has("/api/agent/sessions/{sessionId}/history")).isTrue();
            var schemas = contract.path("components").path("schemas");
            for (var entry : java.util.Map.of("Conversation", ConversationService.Conversation.class,
                    "HistoryPage", ConversationService.HistoryPage.class).entrySet()) {
                var actual = Arrays.stream(entry.getValue().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
                var expected = schemas.path(entry.getKey()).path("properties").propertyNames();
                assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
            }
            assertThat(schemas.path("HistoryPage").path("properties").path("untilSequence").path("type").asString()).isEqualTo("string");
        }
    }
}
