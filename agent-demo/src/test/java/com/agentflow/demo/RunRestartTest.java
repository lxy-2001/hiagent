package com.agentflow.demo;

import com.agentflow.core.AgentRuntime;
import com.agentflow.web.agent.AgentSessionEntity;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentTaskEntity;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = AgentFlowDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:restart;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "agentflow.knowledge.bootstrap.enabled=false",
        "agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32"})
class RunRestartTest {
    private static final Instant CREATED = Instant.parse("2026-09-15T00:00:00Z");

    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTaskRepository tasks;
    @Autowired RunCoordinator coordinator;
    @Autowired RunSseService sse;
    @MockitoBean AgentRuntime runtime;

    @BeforeEach
    void clearDatabase() {
        tasks.deleteAll();
        sessions.deleteAll();
        clearInvocations(runtime);
    }

    @Test
    void twoHundredAndOneOldRowsConvergeInHundredRowBatchesWithoutRuntimeReplay() {
        List<AgentSessionEntity> oldSessions = new ArrayList<>();
        List<AgentTaskEntity> oldTasks = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            String suffix = String.format("%03d", index);
            oldSessions.add(new AgentSessionEntity("session-" + suffix, "owner", "old", CREATED));
            oldTasks.add(new AgentTaskEntity("task-" + suffix, "session-" + suffix, "owner",
                    "old input", index % 2 == 0 ? "QUEUED" : "RUNNING", CREATED));
        }
        sessions.saveAll(oldSessions);
        tasks.saveAllAndFlush(oldTasks);

        assertThat(coordinator.recoverInterrupted()).isTrue();
        assertThat(tasks.findAll()).hasSize(201).allSatisfy(task -> {
            assertThat(task.getStatus()).isEqualTo("FAILED");
            assertThat(task.getTerminationReason()).isEqualTo("PROCESS_INTERRUPTED");
            assertThat(task.getErrorCode()).isEqualTo("PROCESS_INTERRUPTED");
        });
        verifyNoInteractions(runtime);

        RunSnapshot old = coordinator.getOwned("owner", "task-000");
        Instant firstFinishedAt = old.finishedAt();
        assertThat(sse.open("task-000", 0, CREATED.plusSeconds(300),
                new MockHttpServletRequest()).status()).isEqualTo(org.springframework.http.HttpStatus.GONE);
        assertThat(coordinator.recoverInterrupted()).isTrue();
        assertThat(coordinator.getOwned("owner", "task-000").finishedAt()).isEqualTo(firstFinishedAt);
        verifyNoInteractions(runtime);
    }

    @Test
    void aSecondBatchFailureKeepsAdmissionClosedAndRetryResumesWithoutRuntime() {
        RunPersistence persistence = mock(RunPersistence.class);
        List<RunSnapshot> first = batch(0, 100);
        List<RunSnapshot> second = batch(100, 100);
        List<RunSnapshot> last = batch(200, 1);
        when(persistence.convergeInterrupted(anyString(), eq(100), eq(CREATED)))
                .thenReturn(first).thenThrow(new IllegalStateException("second batch unavailable"))
                .thenReturn(second).thenReturn(last);
        AgentRuntime isolatedRuntime = mock(AgentRuntime.class);
        RunCoordinator isolated = RunStartTestAccess.coordinator(isolatedRuntime, persistence, CREATED);
        try {
            assertThat(isolated.recoverInterrupted()).isFalse();
            assertThat(isolated.availability()).isEqualTo(RunCoordinator.Availability.DEGRADED);
            assertThat(isolated.recoverInterrupted()).isTrue();
            assertThat(isolated.availability()).isEqualTo(RunCoordinator.Availability.READY);
            verifyNoInteractions(isolatedRuntime);
            var order = inOrder(persistence);
            order.verify(persistence).convergeInterrupted("", 100, CREATED);
            order.verify(persistence).convergeInterrupted("task-099", 100, CREATED);
            order.verify(persistence).convergeInterrupted("", 100, CREATED);
            order.verify(persistence).convergeInterrupted("task-199", 100, CREATED);
        } finally {
            isolated.close();
        }
    }

    private static List<RunSnapshot> batch(int start, int count) {
        List<RunSnapshot> snapshots = new ArrayList<>();
        for (int index = start; index < start + count; index++) {
            String id = "task-" + String.format("%03d", index);
            snapshots.add(new RunSnapshot(id, id, "session", RunLifecycleStatus.FAILED, "old", null,
                    CREATED, CREATED, CREATED, CREATED, false,
                    RunTerminationReason.PROCESS_INTERRUPTED, null,
                    RunTerminationReason.PROCESS_INTERRUPTED.name(), false,
                    com.agentflow.core.chat.TokenUsage.empty()));
        }
        return snapshots;
    }

    /** Keeps the production restart test independent of package-private Web test helpers. */
    private static final class RunStartTestAccess {
        static RunCoordinator coordinator(AgentRuntime runtime, RunPersistence persistence, Instant now) {
            RunLifecycleProperties limits = new RunLifecycleProperties(1, 1, 2,
                    java.time.Duration.ofSeconds(30), 256, 1_048_576, 16_384, 128,
                    java.time.Duration.ofMinutes(10), 16, 32, 32);
            InMemoryRunEventHub hub = new InMemoryRunEventHub(new tools.jackson.databind.ObjectMapper(),
                    256, 1_048_576, 16_384);
            return new RunCoordinator(runtime, persistence, hub, new RunEventProjector(),
                    new RunResultProjector(), new BoundedRunExecutor(1, 1, Thread::new), limits,
                    java.time.Clock.fixed(now, java.time.ZoneOffset.UTC), System::nanoTime,
                    () -> "unused");
        }
    }
}
