package com.agentflow.web.run;

import com.agentflow.core.AgentRuntime;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RunCancellationTestSupport implements AutoCloseable {
    final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    final RunTestSupport.ControlledTime time = RunTestSupport.timeAt(now);
    final RunPersistence persistence = mock(RunPersistence.class);
    final Map<String, RunSnapshot> snapshots = new ConcurrentHashMap<>();
    final RunCoordinator coordinator;
    private final AtomicInteger ids = new AtomicInteger();

    RunCancellationTestSupport(AgentRuntime runtime) {
        this(runtime, Duration.ofSeconds(30), 1, 4);
    }

    RunCancellationTestSupport(AgentRuntime runtime, Duration queueTimeout, int workers, int capacity) {
        when(persistence.createQueued(any())).thenAnswer(invocation -> {
            RunPersistence.CreateCommand command = invocation.getArgument(0);
            RunSnapshot snapshot = new RunSnapshot(command.taskId(), command.taskId(), command.sessionId(),
                    RunLifecycleStatus.QUEUED, command.input(), null, command.createdAt(), command.createdAt(),
                    null, null, false, null, null, null, false, null);
            snapshots.put(command.taskId(), snapshot);
            return snapshot;
        });
        when(persistence.markRunning(anyString(), any())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            Instant startedAt = invocation.getArgument(1);
            RunSnapshot before = snapshots.get(taskId);
            if (before.status().isTerminal()) {
                return new RunPersistence.StartResult(RunPersistence.StartOutcome.TERMINAL, before);
            }
            RunSnapshot running = new RunSnapshot(taskId, taskId, before.sessionId(),
                    RunLifecycleStatus.RUNNING, before.input(), null, before.createdAt(), startedAt,
                    startedAt, null, before.cancelRequested(), null, null, null, false, null);
            snapshots.put(taskId, running);
            return new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED, running);
        });
        when(persistence.complete(any())).thenAnswer(invocation -> {
            RunResultProjector.FinalProjection projection = invocation.getArgument(0);
            RunSnapshot before = snapshots.get(projection.taskId());
            RunSnapshot terminal = new RunSnapshot(projection.taskId(), projection.taskId(), before.sessionId(),
                    projection.status(), before.input(), projection.finalAnswer(), before.createdAt(),
                    projection.finishedAt(), before.startedAt(), projection.finishedAt(),
                    projection.cancelRequested(), projection.terminationReason(), projection.runtimeReason(),
                    projection.errorCode(), projection.recordingComplete(), projection.usage());
            RunSnapshot winner = snapshots.compute(projection.taskId(), (key, current) ->
                    current.status().isTerminal() ? current : terminal);
            return new RunPersistence.CommittedTerminal(winner, winner == terminal,
                    winner != terminal && !winner.equals(terminal));
        });
        when(persistence.getOwned(anyString(), anyString())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0);
            RunSnapshot snapshot = snapshots.get(invocation.<String>getArgument(1));
            return snapshot == null || !"owner".equals(userId) ? Optional.empty() : Optional.of(snapshot);
        });
        when(persistence.requestCancellation(anyString(), any())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            Instant at = invocation.getArgument(1);
            RunSnapshot before = snapshots.get(taskId);
            if (before == null) return new RunPersistence.CancelResult(RunPersistence.CancelOutcome.MISSING, null);
            if (before.status().isTerminal()) {
                return new RunPersistence.CancelResult(RunPersistence.CancelOutcome.TERMINAL, before);
            }
            RunSnapshot requested = new RunSnapshot(taskId, taskId, before.sessionId(), before.status(),
                    before.input(), null, before.createdAt(), at, before.startedAt(), null,
                    true, null, null, null, false, null);
            snapshots.put(taskId, requested);
            return new RunPersistence.CancelResult(RunPersistence.CancelOutcome.RECORDED, requested);
        });
        RunLifecycleProperties limits = new RunLifecycleProperties(workers, Math.max(1, capacity - workers),
                capacity, queueTimeout, 256, 1_048_576, 16_384, 128, Duration.ofMinutes(10), 16, 32, 32);
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(), 256, 1_048_576, 16_384,
                capacity, 128, Duration.ofMinutes(10).toNanos(), time);
        coordinator = new RunCoordinator(runtime, persistence, hub, new RunEventProjector(),
                new RunResultProjector(), new BoundedRunExecutor(workers, Math.max(1, capacity - workers), Thread::new),
                limits, time, time, this::nextId);
    }

    private String nextId() {
        int sequence = ids.getAndIncrement();
        return sequence % 2 == 0 ? "task-" + (sequence / 2 + 1) : "session-" + (sequence / 2 + 1);
    }

    @Override public void close() { coordinator.close(); }
}
