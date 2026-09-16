package com.agentflow.web.run;

import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunSseHttpTest {
    @Test
    void authenticatedOwnerCanOpenTheExistingRouteAndReceiveCommittedTerminalHistory() {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(), 256, 1_048_576, 16_384,
                2, 2, Duration.ofMinutes(10).toNanos(), System::nanoTime, 2, 2);
        hub.create("task");
        hub.publish("task", new RunEvent.Draft("task", "task", RunEvent.Type.RUN_TERMINATED,
                now, Map.of("status", "SUCCEEDED")));
        hub.markTerminal("task");
        RunLifecycleProperties limits = new RunLifecycleProperties(1, 1, 2, Duration.ofSeconds(30),
                256, 1_048_576, 16_384, 2, Duration.ofMinutes(10), 2, 2, 2);
        RunSseService sse = new RunSseService(hub, limits, Clock.fixed(now, ZoneOffset.UTC));
        AgentTaskService tasks = mock(AgentTaskService.class);
        when(tasks.getTask("owner", "task")).thenReturn(terminal(now));
        MockHttpServletRequest request = new MockHttpServletRequest();
        try {
            var response = new AgentController(tasks, sse).events(RunTestSupport.jwtFor("owner"),
                    "task", "0", request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/event-stream");
            ((RunSseSubscription) request.getAttribute(RunSseService.REQUEST_SUBSCRIPTION)).ready();
            awaitNoSubscriptions(sse);
            assertThat(hub.open("task", 1).status()).isEqualTo(RunEventHub.OpenStatus.DONE);
        } finally { sse.close(); }
    }

    @Test
    void ownerCheckPrecedesCursorValidationAndMissingHistoryReturnsGone() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        when(tasks.getTask("owner", "hidden")).thenThrow(new RunCoordinator.RunNotFoundException());
        AgentController controller = new AgentController(tasks, mock(RunSseService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.events(
                RunTestSupport.jwtFor("owner"), "hidden", "+1", new MockHttpServletRequest()))
                .isInstanceOf(RunCoordinator.RunNotFoundException.class);

        when(tasks.getTask("owner", "known")).thenReturn(terminal(Instant.parse("2026-09-15T00:00:00Z")));
        RunSseService absent = mock(RunSseService.class);
        when(absent.open(eq("known"), eq(0L), any(), any(HttpServletRequest.class)))
                .thenReturn(new RunSseService.OpenResponse(HttpStatus.GONE, null));
        var gone = new AgentController(tasks, absent).events(RunTestSupport.jwtFor("owner"),
                "known", null, new MockHttpServletRequest());
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    static RunSnapshot terminal(Instant now) {
        return new RunSnapshot("task", "task", "session", RunLifecycleStatus.SUCCEEDED, "input", "done",
                now, now, now, now, false, RunTerminationReason.COMPLETED,
                com.agentflow.core.runtime.TerminationReason.COMPLETED, null, false,
                com.agentflow.core.chat.TokenUsage.empty());
    }

    private static void awaitNoSubscriptions(RunSseService service) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (service.activeCount() != 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(service.activeCount()).isZero();
    }
}
