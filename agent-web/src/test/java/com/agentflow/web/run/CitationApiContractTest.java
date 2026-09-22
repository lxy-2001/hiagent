package com.agentflow.web.run;

import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CitationApiContractTest {
    @Test void trackedContractIncludesEvidenceAndAllNewRuntimeReasons() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/feature005-openapi.yaml")) {
            assertThat(input).isNotNull();
            String contract = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(contract).contains("requireEvidence:", "citations:", "CITATION_DATA_UNAVAILABLE",
                    "CITATION_INVALID", "INSUFFICIENT_EVIDENCE", "CITATION_VALIDATION");
        }
    }
    @Test void missingOrExplicitEvidencePolicyHasStableAdmissionSemantics() {
        var service = mock(AgentTaskService.class);
        var accepted = new RunCoordinator.RunAccepted("task", "task", "session", RunLifecycleStatus.QUEUED);
        when(service.create("owner", "question", null, true)).thenReturn(accepted);
        var request = new tools.jackson.databind.ObjectMapper().readValue(
                "{\"input\":\"question\",\"requireEvidence\":true}", AgentController.CreateTaskRequest.class);
        var response = new AgentController(service).createTask(RunTestSupport.jwtFor("owner"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).create("owner", "question", null, true);
        assertThat(new AgentController.CreateTaskRequest("question").requireEvidence()).isFalse();
    }

    @Test void corruptedEvidenceReturns503WithoutCachingOrRawData() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new RunApiExceptionHandler()).build();
        mvc.perform(get("/citation-corruption"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CITATION_DATA_UNAVAILABLE"));
    }

    @org.springframework.web.bind.annotation.RestController
    static class FailureController {
        @org.springframework.web.bind.annotation.GetMapping("/citation-corruption")
        public Object read() { throw new CitationSnapshotCodec.UnavailableException(); }
    }
}
