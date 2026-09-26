package com.agentflow.demo.delivery;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DeliveryFlowTest {
    @Test void confirmedMemoryCitationsAndApprovedMcpWriteFormOneConversation() throws Exception {
        try (var fixture = DeliveryDemoLauncher.start("happy-path", 0)) {
            var first = fixture.create("remember", null, false);
            fixture.awaitTerminal(first.path("taskId").asString());
            String session = first.path("sessionId").asString();
            fixture.request("PUT", "/api/agent/sessions/" + session + "/memories/project_stack", Map.of("value", "Java17", "expectedVersion", "0"));
            var research = fixture.create("research", session, true);
            String researchId = research.path("taskId").asString();
            var answer = fixture.awaitTerminal(researchId);
            assertThat(answer.path("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(answer.path("citations").size()).isEqualTo(1);
            assertThat(answer.path("finalAnswer").asString()).contains("[S1]");
            assertThat(fixture.requests(researchId).get(0).messages().toString()).contains("Java17");
            fixture.evidence("happy-path-research", researchId, answer);
            var note = fixture.create("note", session, false);
            String id = note.path("taskId").asString();
            var approval = fixture.awaitApproval(id);
            assertThat(fixture.writes()).isZero();
            fixture.decide(id, approval.path("approvalId").asString(), "APPROVE");
            var done = fixture.awaitTerminal(id);
            assertThat(done.path("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(done.path("finalAnswer").asString()).isEqualTo("RECEIPT_CONFIRMED");
            assertThat(done.path("recordingComplete").asBoolean()).isTrue();
            assertThat(fixture.writes()).isEqualTo(1);
            assertThat(fixture.request("GET", "/api/agent/tasks/" + id + "/steps", null).toString()).contains("TOOL_RESULT", "TERMINATION");
            fixture.evidence("happy-path", id, done);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"rejected", "cancelled"})
    void rejectedAndCancelledNeverWrite(String scenario) throws Exception {
        try (var fixture = DeliveryDemoLauncher.start(scenario, 0)) {
            String id = fixture.create("note", null, false).path("taskId").asString();
            var approval = fixture.awaitApproval(id);
            assertThat(fixture.writes()).isZero();
            if (scenario.equals("rejected")) fixture.decide(id, approval.path("approvalId").asString(), "REJECT");
            else fixture.request("POST", "/api/agent/tasks/" + id + "/cancel", null);
            var done = fixture.awaitTerminal(id);
            assertThat(done.path("status").asString()).isEqualTo(scenario.equals("rejected") ? "FAILED" : "CANCELLED");
            if (scenario.equals("rejected")) assertThat(done.path("terminationReason").asString()).isEqualTo("APPROVAL_REJECTED");
            assertThat(fixture.requests(id)).hasSize(1);
            assertThat(fixture.writes()).isZero();
            assertThat(fixture.events(id, null).body().split("event: ?RUN_TERMINATED", -1)).hasSize(2);
            fixture.evidence(scenario, id, done);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"empty-evidence", "dependency-failure"})
    void missingOrFailedEvidenceCannotBecomeSuccess(String scenario) throws Exception {
        try (var fixture = DeliveryDemoLauncher.start(scenario, 0)) {
            String id = fixture.create("research", null, true).path("taskId").asString();
            var done = fixture.awaitTerminal(id);
            assertThat(done.path("status").asString()).isEqualTo("FAILED");
            assertThat(done.path("citations").isEmpty()).isTrue();
            assertThat(fixture.writes()).isZero();
            var steps = fixture.request("GET", "/api/agent/tasks/" + id + "/steps", null);
            if (scenario.equals("empty-evidence")) assertThat(done.path("terminationReason").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
            else assertThat(steps.toString()).contains("RAG_SOURCE_INVALID");
            fixture.evidence(scenario, id, done);
        }
    }
}
