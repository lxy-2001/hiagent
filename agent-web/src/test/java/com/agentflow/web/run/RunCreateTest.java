package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunCreateTest {
    private RunCoordinator coordinator;

    @AfterEach void close() { if (coordinator != null) coordinator.close(); }

    @Test
    void returnsQueuedReceiptAfterCommittedCreateAndPublishesCreated() throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenAnswer(invocation -> {
            RunPersistence.CreateCommand c = invocation.getArgument(0);
            return queued(c.taskId(), c.sessionId(), c.input(), now);
        });
        when(persistence.markRunning(any(), any())).thenReturn(new RunPersistence.StartResult(
                RunPersistence.StartOutcome.TERMINAL, terminal("task-1", "session-1", "input", now)));
        InMemoryRunEventHub hub = hub(now, RunLifecycleProperties.defaults());
        coordinator = coordinator(persistence, hub, () -> "task-1", () -> "session-1", request ->
                AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty()));

        RunCoordinator.RunAccepted accepted = coordinator.create("user-1", " input ");

        assertThat(accepted.status()).isEqualTo(RunLifecycleStatus.QUEUED);
        assertThat(accepted.runId()).isEqualTo(accepted.taskId());
        assertThat(hub.replay("task-1", 0).frames()).extracting(frame -> frame.event().type())
                .contains(RunEvent.Type.RUN_CREATED);
        verify(persistence).createQueued(any());
    }

    @Test
    void rejectsBeforeDatabaseWhenAllThirtySixReservationsAreHeld() throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        RunPersistence persistence = mock(RunPersistence.class);
        CountDownLatch block = new CountDownLatch(1);
        AtomicInteger ids = new AtomicInteger();
        when(persistence.createQueued(any())).thenAnswer(i -> {
            RunPersistence.CreateCommand c = i.getArgument(0); return queued(c.taskId(), c.sessionId(), c.input(), now);
        });
        when(persistence.markRunning(any(), any())).thenAnswer(i -> new RunPersistence.StartResult(
                RunPersistence.StartOutcome.STARTED, running(i.getArgument(0), "s", "x", now)));
        RunLifecycleProperties p = RunLifecycleProperties.defaults();
        coordinator = new RunCoordinator((query, timeout, cancellation) -> com.agentflow.core.context.ContextSeed.empty(), (request, sink, options) -> {
            try { block.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        }, persistence, hub(now, p),
                new RunEventProjector(), new RunResultProjector(), new BoundedRunExecutor(4, 32, Thread::new),
                p, Clock.fixed(now, ZoneOffset.UTC), System::nanoTime, () -> "id-" + ids.incrementAndGet());
        for (int i = 0; i < 36; i++) coordinator.create("user", "input");

        assertThatThrownBy(() -> coordinator.create("user", "overflow"))
                .isInstanceOf(RunCoordinator.RunCapacityException.class);
        block.countDown();
    }

    private RunCoordinator coordinator(RunPersistence persistence, RunEventHub hub,
                                       java.util.function.Supplier<String> first,
                                       java.util.function.Supplier<String> second,
                                       java.util.function.Function<com.agentflow.core.AgentRequest, AgentResult> result) {
        AtomicInteger call = new AtomicInteger();
        return new RunCoordinator((query, timeout, cancellation) -> com.agentflow.core.context.ContextSeed.empty(), (request, sink, options) -> result.apply(request), persistence, hub,
                new RunEventProjector(), new RunResultProjector(), new BoundedRunExecutor(1, 1, Thread::new),
                new RunLifecycleProperties(1, 1, 2, java.time.Duration.ofSeconds(30), 256, 1_048_576,
                        16_384, 128, java.time.Duration.ofMinutes(10), 16, 32, 32),
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC), System::nanoTime,
                () -> call.getAndIncrement() == 0 ? first.get() : second.get());
    }

    private static RunSnapshot queued(String id, String session, String input, Instant now) {
        return new RunSnapshot(id, id, session, RunLifecycleStatus.QUEUED, input, null, now, now,
                null, null, false, null, null, null, false, null);
    }
    private static InMemoryRunEventHub hub(Instant now, RunLifecycleProperties p) {
        return new InMemoryRunEventHub(new ObjectMapper(), p.eventWindowCount(), p.eventWindowBytes(),
                p.eventFrameBytes(), p.inFlightCapacity(), p.terminalCacheCapacity(),
                p.terminalCacheTtl().toNanos(), () -> 0L);
    }
    private static RunSnapshot running(String id, String session, String input, Instant now) {
        return new RunSnapshot(id, id, session, RunLifecycleStatus.RUNNING, input, null, now, now,
                now, null, false, null, null, null, false, null);
    }
    private static RunSnapshot terminal(String id, String session, String input, Instant now) {
        return new RunSnapshot(id, id, session, RunLifecycleStatus.FAILED, input, null, now, now,
                now, now, false, RunTerminationReason.INTERNAL_ERROR, null, "INTERNAL_ERROR", false, null);
    }
}
