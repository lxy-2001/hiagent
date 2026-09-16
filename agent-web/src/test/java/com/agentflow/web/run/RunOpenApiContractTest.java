package com.agentflow.web.run;

import com.agentflow.web.agent.AgentController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RunOpenApiContractTest {
    @Test
    void trackedContractFixtureContainsTheFiveRunOperationsAndSseErrors() throws IOException {
        String contract;
        try (var stream = getClass().getResourceAsStream("/contracts/run-lifecycle.yaml")) {
            assertThat(stream).isNotNull();
            contract = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(contract).contains("/api/agent/tasks:",
                "/api/agent/tasks/{taskId}:",
                "/api/agent/tasks/{taskId}/steps:",
                "/api/agent/tasks/{taskId}/events:",
                "/api/agent/tasks/{taskId}/cancel:",
                "text/event-stream", "Last-Event-ID", "EVENT_HISTORY_UNAVAILABLE",
                "SUBSCRIPTION_CAPACITY_EXCEEDED");
    }

    @Test
    void controllerKeepsOneMethodForEachVersionedOperation() {
        assertThat(AgentController.class.getDeclaredMethods())
                .filteredOn(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class))
                .hasSize(5);
    }
}
