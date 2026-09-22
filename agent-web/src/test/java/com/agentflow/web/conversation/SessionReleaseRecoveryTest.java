package com.agentflow.web.conversation;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.ContextSeed;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionReleaseRecoveryTest {
    @Test
    void confirmedTerminalDoesNotReleaseSessionUntilWorkerExit() {
        var callbacks = new ArrayList<Runnable[]>();
        var persistence = persistence();
        var executor = executor(callbacks);
        try (var coordinator = coordinator(persistence, executor)) {
            coordinator.create("owner", "input", "session");
            callbacks.get(0)[0].run();
            assertThatThrownBy(() -> coordinator.create("owner", "next", "session")).isInstanceOf(RunCoordinator.SessionBusyException.class);
            assertThat(coordinator.inFlightCount()).isOne();
            callbacks.get(0)[2].run();
            assertThat(coordinator.inFlightCount()).isZero();
            assertThat(coordinator.create("owner", "next", "session").sessionId()).isEqualTo("session");
            callbacks.get(0)[2].run();
            assertThat(coordinator.inFlightCount()).isOne();
        }
    }

    @Test
    void workerExitWithPendingFinalKeepsSessionUntilConfirmedRecovery() {
        var callbacks = new ArrayList<Runnable[]>();
        var persistence = persistence();
        var failed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failed.get()) throw new IllegalStateException("commit unavailable");
            return terminal(invocation.getArgument(0));
        }).when(persistence).complete(any());
        try (var coordinator = coordinator(persistence, executor(callbacks))) {
            coordinator.create("owner", "input", "session");
            callbacks.get(0)[0].run();
            callbacks.get(0)[2].run();
            assertThat(coordinator.inFlightCount()).isOne();
            failed.set(false);
            coordinator.maintainOnce();
            assertThat(coordinator.inFlightCount()).isZero();
            assertThat(coordinator.create("owner", "next", "session").sessionId()).isEqualTo("session");
        }
    }

    @Test
    void uncertainCreateRetainsSessionAndRecoveryReleasesWithoutRetryingCreate() {
        var persistence = persistence();
        doThrow(new IllegalStateException("confirmation lost")).when(persistence).createQueued(any());
        var now = Instant.now();
        when(persistence.getOwned("owner", "run-1")).thenReturn(Optional.of(new RunSnapshot("run-1", "run-1", "session",
                RunLifecycleStatus.QUEUED, "input", null, now, now, null, null, false, null, null, null, false, null)));
        var callbacks = new ArrayList<Runnable[]>();
        try (var coordinator = coordinator(persistence, executor(callbacks))) {
            assertThatThrownBy(() -> coordinator.create("owner", "input", "session")).isInstanceOf(RunCoordinator.RunUnavailableException.class);
            assertThat(coordinator.inFlightCount()).isOne();
            coordinator.maintainOnce();
            assertThat(coordinator.inFlightCount()).isZero();
            verify(persistence, times(1)).createQueued(any());
            assertThat(callbacks).isEmpty();
        }
    }

    @Test
    void dispatchRejectionReleasesSessionAfterCommittedFailure() {
        var persistence = persistence();
        var executor = mock(BoundedRunExecutor.class);
        when(executor.dispatch(any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return new BoundedRunExecutor.Dispatch(false, null);
        });
        try (var coordinator = coordinator(persistence, executor)) {
            coordinator.create("owner", "input", "session");
            assertThat(coordinator.inFlightCount()).isZero();
            coordinator.create("owner", "next", "session");
            assertThat(coordinator.inFlightCount()).isZero();
            verify(persistence, times(2)).complete(any());
        }
    }

    private static RunPersistence persistence() {
        var persistence = mock(RunPersistence.class);
        when(persistence.ownsSession(anyString(), anyString())).thenReturn(true);
        when(persistence.createQueued(any())).thenAnswer(invocation -> {
            RunPersistence.CreateCommand c = invocation.getArgument(0);
            return new RunSnapshot(c.taskId(), c.taskId(), c.sessionId(), RunLifecycleStatus.QUEUED, c.input(), null,
                    c.createdAt(), c.createdAt(), null, null, false, null, null, null, false, null);
        });
        when(persistence.markRunning(anyString(), any())).thenReturn(new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED, null));
        when(persistence.complete(any())).thenAnswer(invocation -> terminal(invocation.getArgument(0)));
        return persistence;
    }
    private static RunPersistence.CommittedTerminal terminal(RunResultProjector.FinalProjection p) {
        return new RunPersistence.CommittedTerminal(new RunSnapshot(p.taskId(), p.taskId(), "session", p.status(), "input", p.finalAnswer(),
                p.finishedAt(), p.finishedAt(), p.finishedAt(), p.finishedAt(), p.cancelRequested(), p.terminationReason(),
                p.runtimeReason(), p.errorCode(), p.recordingComplete(), p.usage()), true, false);
    }
    private static BoundedRunExecutor executor(List<Runnable[]> callbacks) {
        var executor = mock(BoundedRunExecutor.class);
        when(executor.dispatch(any(), any(), any())).thenAnswer(invocation -> {
            callbacks.add(new Runnable[]{invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)});
            return new BoundedRunExecutor.Dispatch(true, mock(BoundedRunExecutor.TaskHandle.class));
        });
        return executor;
    }
    private static RunCoordinator coordinator(RunPersistence persistence, BoundedRunExecutor executor) {
        var ids = new AtomicInteger();
        AgentRuntime runtime = (request, sink, options) -> AgentResult.success(request.taskId(), "answer", List.of(), TokenUsage.empty());
        return new RunCoordinator((q, t, c) -> ContextSeed.empty(), runtime, persistence, mock(RunEventHub.class), new RunEventProjector(),
                new RunResultProjector(), executor, RunLifecycleProperties.defaults(), Clock.systemUTC(), System::nanoTime, () -> "run-" + ids.incrementAndGet());
    }
}
