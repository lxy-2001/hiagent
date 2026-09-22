package com.agentflow.web.conversation;

import com.agentflow.core.AgentRuntime;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAdmissionTest {
    @Test
    void concurrentSameSessionAcceptsExactlyOneAndDifferentSessionStillWorks() throws Exception {
        var persistence = persistence();
        var pool = Executors.newFixedThreadPool(8);
        try (var coordinator = coordinator(persistence)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 50; i++) results.add(pool.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                try { coordinator.create("owner", "input", "session"); return true; }
                catch (RunCoordinator.SessionBusyException expected) { return false; }
            }));
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isOne();
            assertThat(coordinator.inFlightCount()).isOne();
            assertThat(coordinator.create("owner", "other", "other-session").sessionId()).isEqualTo("other-session");
            verify(persistence, times(2)).createQueued(any());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void checksOwnerBeforeBusyAndInvalidInputNeverReserves() {
        var persistence = persistence();
        when(persistence.ownsSession("stranger", "session")).thenReturn(false);
        try (var coordinator = coordinator(persistence)) {
            coordinator.create("owner", "input", "session");
            assertThatThrownBy(() -> coordinator.create("stranger", "input", "session"))
                    .isInstanceOf(RunCoordinator.RunNotFoundException.class);
            assertThatThrownBy(() -> coordinator.create("owner", "x".repeat(7993) + "token=x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> coordinator.create("owner", " ".repeat(8000) + "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(coordinator.inFlightCount()).isOne();
            verify(persistence, times(1)).createQueued(any());
        }
    }

    @Test
    void persistsOnlySanitizedInputAndDoesNotSplitTitleSurrogatePair() {
        var persistence = persistence();
        try (var coordinator = coordinator(persistence)) {
            coordinator.create("owner", "x".repeat(47) + "😀 token=private-value");
            var command = org.mockito.ArgumentCaptor.forClass(RunPersistence.CreateCommand.class);
            verify(persistence).createQueued(command.capture());
            assertThat(command.getValue().input()).contains("token=[redacted]").doesNotContain("private-value");
            assertThat(command.getValue().title()).isEqualTo("x".repeat(47));
        }
    }

    @Test
    void definiteCreateRejectionReleasesCapacityWithoutDegradingOrRetryingCreate() {
        var persistence = persistence();
        doThrow(new RunPersistence.CreateRejectedException("TURN_SEQUENCE_EXHAUSTED")).when(persistence).createQueued(any());
        try (var coordinator = coordinator(persistence)) {
            assertThatThrownBy(() -> coordinator.create("owner", "input", "session"))
                    .isInstanceOfSatisfying(RunCoordinator.RunUnavailableException.class,
                            ex -> assertThat(ex.code()).isEqualTo("TURN_SEQUENCE_EXHAUSTED"));
            assertThat(coordinator.inFlightCount()).isZero();
            assertThat(coordinator.availability()).isEqualTo(RunCoordinator.Availability.READY);
            verify(persistence, times(1)).createQueued(any());
        }
    }

    @Test
    void fullCapacityDoesNotLeakANewSessionReservation() {
        var persistence = persistence();
        try (var coordinator = coordinator(persistence)) {
            for (int i = 0; i < 36; i++) coordinator.create("owner", "input", "s-" + i);
            assertThatThrownBy(() -> coordinator.create("owner", "input", "overflow"))
                    .isInstanceOf(RunCoordinator.RunCapacityException.class);
            assertThat(coordinator.inFlightCount()).isEqualTo(36);
            verify(persistence, times(36)).createQueued(any());
        }
    }

    @Test
    void busyHttpResponseUsesRealHandlerAndNeverEchoesInput() throws Exception {
        var service = mock(com.agentflow.web.agent.AgentTaskService.class);
        when(service.create("owner", "private-input", "session")).thenThrow(new RunCoordinator.SessionBusyException());
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "none").subject("owner").build();
        var resolver = new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
            public boolean supportsParameter(org.springframework.core.MethodParameter p) { return p.getParameterType() == org.springframework.security.oauth2.jwt.Jwt.class; }
            public Object resolveArgument(org.springframework.core.MethodParameter p, org.springframework.web.method.support.ModelAndViewContainer m,
                    org.springframework.web.context.request.NativeWebRequest w, org.springframework.web.bind.support.WebDataBinderFactory b) { return jwt; }
        };
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.agentflow.web.agent.AgentController(service))
                .setControllerAdvice(new RunApiExceptionHandler()).setCustomArgumentResolvers(resolver).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agent/tasks")
                        .contentType("application/json").content("{\"input\":\"private-input\",\"sessionId\":\"session\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SESSION_BUSY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-input"))));
    }

    private static RunPersistence persistence() {
        var persistence = mock(RunPersistence.class);
        when(persistence.ownsSession(anyString(), anyString())).thenReturn(true);
        when(persistence.createQueued(any())).thenAnswer(invocation -> {
            RunPersistence.CreateCommand c = invocation.getArgument(0);
            return new RunSnapshot(c.taskId(), c.taskId(), c.sessionId(), RunLifecycleStatus.QUEUED, c.input(), null,
                    c.createdAt(), c.createdAt(), null, null, false, null, null, null, false, null);
        });
        return persistence;
    }

    private static RunCoordinator coordinator(RunPersistence persistence) {
        var executor = mock(BoundedRunExecutor.class);
        when(executor.dispatch(any(), any(), any())).thenReturn(new BoundedRunExecutor.Dispatch(true, mock(BoundedRunExecutor.TaskHandle.class)));
        var ids = new AtomicInteger();
        return new RunCoordinator((query, timeout, cancellation) -> com.agentflow.core.context.ContextSeed.empty(), mock(AgentRuntime.class), persistence, mock(RunEventHub.class), new RunEventProjector(),
                new RunResultProjector(), executor, RunLifecycleProperties.defaults(), Clock.systemUTC(), System::nanoTime,
                () -> "id-" + ids.incrementAndGet());
    }
}
