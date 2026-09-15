package com.agentflow.web.run;

import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentTaskService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunCancelApiTest {
    @Test
    void runningCancellationIsAcceptedAndUsesTheAuthenticatedOwner() {
        AgentTaskService service = mock(AgentTaskService.class);
        RunSnapshot running = snapshot(RunLifecycleStatus.RUNNING, true);
        when(service.cancel("owner", "task")).thenReturn(new RunCoordinator.CancelReply(running, true));

        var response = new AgentController(service).cancel(RunTestSupport.jwtFor("owner"), "task");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(running);
        verify(service).cancel("owner", "task");
    }

    @Test
    void confirmedTerminalCancellationIsIdempotentAndReturnsOk() {
        AgentTaskService service = mock(AgentTaskService.class);
        RunSnapshot terminal = snapshot(RunLifecycleStatus.CANCELLED, true);
        when(service.cancel("owner", "task")).thenReturn(new RunCoordinator.CancelReply(terminal, false));

        var response = new AgentController(service).cancel(RunTestSupport.jwtFor("owner"), "task");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(terminal);
    }

    @Test
    void ownerMaskingAndPendingPersistenceMapToStableHttpErrors() {
        RunApiExceptionHandler errors = new RunApiExceptionHandler();
        assertThat(errors.notFound().getStatusCode().value()).isEqualTo(404);
        var unavailable = errors.unavailable(new RunCoordinator.RunUnavailableException(
                "PERSISTENCE_UNAVAILABLE", "task"));
        assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
        assertThat(unavailable.getBody().code()).isEqualTo("PERSISTENCE_UNAVAILABLE");
        assertThat(unavailable.getBody().taskId()).isEqualTo("task");
    }

    private static RunSnapshot snapshot(RunLifecycleStatus status, boolean cancelRequested) {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        if (status == RunLifecycleStatus.RUNNING) {
            return new RunSnapshot("task", "task", "session", status, "input", null,
                    now, now, now, null, cancelRequested, null, null, null, false, null);
        }
        return new RunSnapshot("task", "task", "session", status, "input", null,
                now, now, null, now, cancelRequested, RunTerminationReason.CANCELLED,
                null, "CANCELLED", false, null);
    }
}
