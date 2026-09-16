package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class RunConvergenceTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void uncertainCreateKeepsItsReservationAndFailsTheConfirmedRowWithoutStartingRuntime() {
        RunPersistence persistence = mock(RunPersistence.class);
        RunSnapshot queued = queued("task", "session");
        when(persistence.createQueued(any())).thenThrow(new IllegalStateException("confirmation lost"));
        when(persistence.getOwned("owner", "task"))
                .thenReturn(Optional.empty(), Optional.of(queued));
        when(persistence.complete(any())).thenAnswer(invocation -> new RunPersistence.CommittedTerminal(
                terminal(invocation.getArgument(0), "session"), true, false));
        AtomicInteger runtimeCalls = new AtomicInteger();
        RunCoordinator coordinator = coordinator((request, sink, options) -> {
            runtimeCalls.incrementAndGet();
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        }, persistence);
        try {
            assertThatThrownBy(() -> coordinator.create("owner", "hello"))
                    .isInstanceOfSatisfying(RunCoordinator.RunUnavailableException.class, error -> {
                        assertThat(error.code()).isEqualTo("PERSISTENCE_UNAVAILABLE");
                        assertThat(error.taskId()).isEqualTo("task");
                    });
            assertThat(coordinator.inFlightCount()).isEqualTo(1);
            assertThat(coordinator.availability()).isEqualTo(RunCoordinator.Availability.DEGRADED);

            coordinator.maintainOnce();
            assertThat(runtimeCalls).hasValue(0);
            assertThat(coordinator.inFlightCount()).isEqualTo(1);
            assertThat(coordinator.availability()).isEqualTo(RunCoordinator.Availability.DEGRADED);

            coordinator.maintainOnce();
            ArgumentCaptor<RunResultProjector.FinalProjection> projection =
                    ArgumentCaptor.forClass(RunResultProjector.FinalProjection.class);
            verify(persistence, timeout(2_000)).complete(projection.capture());
            verify(persistence, times(1)).createQueued(any());
            verify(persistence, never()).markRunning(anyString(), any());
            assertThat(projection.getValue().status()).isEqualTo(RunLifecycleStatus.FAILED);
            assertThat(projection.getValue().terminationReason())
                    .isEqualTo(RunTerminationReason.DISPATCH_REJECTED);
            assertThat(runtimeCalls).hasValue(0);
            assertThat(coordinator.inFlightCount()).isZero();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void uncertainStartIsConfirmedByMaintenanceButOnlyTheOriginalWorkerCallsRuntime() {
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenReturn(queued("task", "session"));
        when(persistence.markRunning("task", NOW))
                .thenThrow(new IllegalStateException("confirmation lost"))
                .thenReturn(new RunPersistence.StartResult(
                        RunPersistence.StartOutcome.ALREADY_RUNNING, running("task", "session")));
        when(persistence.complete(any())).thenAnswer(invocation -> new RunPersistence.CommittedTerminal(
                terminal(invocation.getArgument(0), "session"), true, false));
        AtomicInteger runtimeCalls = new AtomicInteger();
        CountDownLatch runtimeCalled = new CountDownLatch(1);
        RunCoordinator coordinator = coordinator((request, sink, options) -> {
            runtimeCalls.incrementAndGet();
            runtimeCalled.countDown();
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        }, persistence);
        try {
            coordinator.create("owner", "hello");
            awaitCondition(() -> coordinator.control("task").orElseThrow().pending().isPresent());
            assertThat(coordinator.control("task").orElseThrow().pending().orElseThrow().kind())
                    .isEqualTo(RunControl.PendingKind.START_UNCERTAIN);
            assertThat(runtimeCalls).hasValue(0);

            coordinator.maintainOnce();

            assertThat(await(runtimeCalled)).isTrue();
            verify(persistence, times(2)).markRunning("task", NOW);
            assertThat(runtimeCalls).hasValue(1);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void cancellationDuringAnUncertainStartStopsTheRuntimeCallAfterConfirmation() {
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenReturn(queued("task", "session"));
        when(persistence.markRunning("task", NOW))
                .thenThrow(new IllegalStateException("confirmation lost"))
                .thenReturn(new RunPersistence.StartResult(
                        RunPersistence.StartOutcome.ALREADY_RUNNING, running("task", "session")));
        when(persistence.complete(any())).thenAnswer(invocation -> new RunPersistence.CommittedTerminal(
                terminal(invocation.getArgument(0), "session"), true, false));
        AtomicInteger runtimeCalls = new AtomicInteger();
        RunCoordinator coordinator = coordinator((request, sink, options) -> {
            runtimeCalls.incrementAndGet();
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        }, persistence);
        try {
            coordinator.create("owner", "hello");
            awaitCondition(() -> coordinator.control("task").orElseThrow().pending().isPresent());

            assertThatThrownBy(() -> coordinator.cancel("owner", "task"))
                    .isInstanceOf(RunCoordinator.RunUnavailableException.class);
            coordinator.maintainOnce();

            ArgumentCaptor<RunResultProjector.FinalProjection> projection =
                    ArgumentCaptor.forClass(RunResultProjector.FinalProjection.class);
            verify(persistence, timeout(2_000)).complete(projection.capture());
            assertThat(projection.getValue().status()).isEqualTo(RunLifecycleStatus.CANCELLED);
            assertThat(projection.getValue().cancelRequested()).isTrue();
            assertThat(runtimeCalls).hasValue(0);
            verify(persistence, never()).requestCancellation(anyString(), any());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void failedStartupRecoveryCannotBeReopenedByAnEmptyMaintenancePass() {
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.convergeInterrupted(anyString(), eq(100), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        RunCoordinator coordinator = coordinator((request, sink, options) -> null, persistence);
        try {
            assertThat(coordinator.recoverInterrupted()).isFalse();
            coordinator.maintainOnce();
            coordinator.markReady();

            assertThat(coordinator.availability()).isEqualTo(RunCoordinator.Availability.DEGRADED);
            assertThatThrownBy(() -> coordinator.create("owner", "hello"))
                    .isInstanceOf(RunCoordinator.RunUnavailableException.class);
            verify(persistence, never()).createQueued(any());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void finalWriteWaitsUntilTheInFlightCancellationWriteReleasesItsClaim() throws Exception {
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenReturn(queued("task", "session"));
        RunSnapshot running = running("task", "session");
        when(persistence.markRunning("task", NOW)).thenReturn(new RunPersistence.StartResult(
                RunPersistence.StartOutcome.STARTED, running));
        when(persistence.getOwned("owner", "task")).thenReturn(Optional.of(running));
        CountDownLatch cancellationWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellationWrite = new CountDownLatch(1);
        when(persistence.requestCancellation("task", NOW)).thenAnswer(invocation -> {
            cancellationWriteEntered.countDown();
            assertThat(releaseCancellationWrite.await(2, TimeUnit.SECONDS)).isTrue();
            return new RunPersistence.CancelResult(RunPersistence.CancelOutcome.RECORDED, running);
        });
        CountDownLatch runtimeEntered = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        CountDownLatch completeEntered = new CountDownLatch(1);
        when(persistence.complete(any())).thenAnswer(invocation -> {
            completeEntered.countDown();
            return new RunPersistence.CommittedTerminal(
                    terminal(invocation.getArgument(0), "session"), true, false);
        });
        AgentRuntime runtime = (request, sink, options) -> {
            runtimeEntered.countDown();
            assertThat(await(releaseRuntime)).isTrue();
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        };
        RunCoordinator coordinator = coordinator(runtime, persistence);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            coordinator.create("owner", "hello");
            assertThat(runtimeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<RunCoordinator.CancelReply> cancellation = caller.submit(
                    () -> coordinator.cancel("owner", "task"));
            assertThat(cancellationWriteEntered.await(2, TimeUnit.SECONDS)).isTrue();

            releaseRuntime.countDown();
            assertThat(completeEntered.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseCancellationWrite.countDown();
            cancellation.get(2, TimeUnit.SECONDS);
            coordinator.maintainOnce();
            assertThat(completeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            verify(persistence, times(1)).complete(any());
        } finally {
            releaseRuntime.countDown();
            releaseCancellationWrite.countDown();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            coordinator.close();
        }
    }

    @Test
    void foreignOwnerStillGetsNotFoundWhileTheRealOwnerHasAPendingWrite() throws Exception {
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenReturn(queued("task", "session"));
        RunSnapshot running = running("task", "session");
        when(persistence.markRunning("task", NOW)).thenReturn(new RunPersistence.StartResult(
                RunPersistence.StartOutcome.STARTED, running));
        when(persistence.getOwned("foreign", "task")).thenReturn(Optional.empty());
        when(persistence.getOwned("owner", "task")).thenReturn(Optional.of(running));
        CountDownLatch runtimeEntered = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        RunCoordinator coordinator = coordinator((request, sink, options) -> {
            runtimeEntered.countDown();
            assertThat(await(releaseRuntime)).isTrue();
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        }, persistence);
        try {
            coordinator.create("owner", "hello");
            assertThat(runtimeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            coordinator.control("task").orElseThrow().markCancellationPending();

            assertThatThrownBy(() -> coordinator.getOwned("foreign", "task"))
                    .isInstanceOf(RunCoordinator.RunNotFoundException.class);
            assertThatThrownBy(() -> coordinator.getOwned("owner", "task"))
                    .isInstanceOf(RunCoordinator.RunUnavailableException.class);
        } finally {
            releaseRuntime.countDown();
            coordinator.close();
        }
    }

    private static RunCoordinator coordinator(AgentRuntime runtime, RunPersistence persistence) {
        RunLifecycleProperties limits = new RunLifecycleProperties(1, 1, 2,
                Duration.ofSeconds(30), 256, 1_048_576, 16_384, 128,
                Duration.ofMinutes(10), 16, 32, 32);
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(),
                256, 1_048_576, 16_384, 2, 128,
                Duration.ofMinutes(10).toNanos(), System::nanoTime);
        AtomicInteger ids = new AtomicInteger();
        return new RunCoordinator(runtime, persistence, hub,
                new RunEventProjector(), new RunResultProjector(),
                new BoundedRunExecutor(1, 1, Thread::new), limits,
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime,
                () -> ids.getAndIncrement() == 0 ? "task" : "session");
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static RunSnapshot queued(String taskId, String sessionId) {
        return new RunSnapshot(taskId, taskId, sessionId, RunLifecycleStatus.QUEUED,
                "hello", null, NOW, NOW, null, null, false,
                null, null, null, false, null);
    }

    private static RunSnapshot running(String taskId, String sessionId) {
        return new RunSnapshot(taskId, taskId, sessionId, RunLifecycleStatus.RUNNING,
                "hello", null, NOW, NOW, NOW, null, false,
                null, null, null, false, null);
    }

    private static RunSnapshot terminal(RunResultProjector.FinalProjection projection,
                                        String sessionId) {
        return new RunSnapshot(projection.taskId(), projection.taskId(), sessionId,
                projection.status(), "hello", projection.finalAnswer(), NOW, NOW, NOW,
                projection.finishedAt(), projection.cancelRequested(), projection.terminationReason(),
                projection.runtimeReason(), projection.errorCode(), projection.recordingComplete(),
                projection.usage());
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
