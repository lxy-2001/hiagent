package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunShutdownTest {
    @Test
    void oneFiveSecondDeadlineStopsAdmissionAndDoesNotInventDatabaseCompletion()
            throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        CountDownLatch finalWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalWrite = new CountDownLatch(1);
        AgentRuntime runtime = (request, sink, options) -> {
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        };
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenAnswer(invocation ->
                queued(invocation.getArgument(0), now));
        when(persistence.markRunning(anyString(), any())).thenAnswer(invocation ->
                new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED,
                        running(invocation.getArgument(0), now)));
        when(persistence.complete(any())).thenAnswer(invocation -> {
            finalWriteEntered.countDown();
            assertThat(releaseFinalWrite.await(15, TimeUnit.SECONDS)).isTrue();
            RunResultProjector.FinalProjection projection = invocation.getArgument(0);
            return new RunPersistence.CommittedTerminal(success(projection, now), true, false);
        });

        RunLifecycleProperties limits = new RunLifecycleProperties(1, 1, 2,
                Duration.ofSeconds(30), 256, 1_048_576, 16_384, 128,
                Duration.ofMinutes(10), 16, 32, 32);
        InMemoryRunEventHub hub = spy(new InMemoryRunEventHub(new ObjectMapper(),
                256, 1_048_576, 16_384, 2, 128,
                Duration.ofMinutes(10).toNanos(), System::nanoTime));
        AtomicInteger ids = new AtomicInteger();
        RunCoordinator coordinator = new RunCoordinator((query, timeout, cancellation) -> com.agentflow.core.context.ContextSeed.empty(), runtime, persistence, hub,
                new RunEventProjector(), new RunResultProjector(),
                new BoundedRunExecutor(1, 1, Thread::new), limits,
                Clock.fixed(now, ZoneOffset.UTC), System::nanoTime,
                () -> "id-" + ids.getAndIncrement());

        coordinator.create("owner", "first");
        coordinator.create("owner", "second");
        assertThat(finalWriteEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.inFlightCount()).isEqualTo(2);

        long started = System.nanoTime();
        coordinator.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isBetween(Duration.ofMillis(4_500), Duration.ofMillis(6_000));
        assertThat(coordinator.availability()).isEqualTo(RunCoordinator.Availability.STOPPING);
        verify(hub).closeSubscriptions();
        assertThat(coordinator.inFlightCount()).isEqualTo(2);
        assertThatThrownBy(() -> coordinator.create("owner", "after close"))
                .isInstanceOf(RunCoordinator.RunUnavailableException.class)
                .extracting(error -> ((RunCoordinator.RunUnavailableException) error).code())
                .isEqualTo("SERVICE_STOPPING");

        releaseFinalWrite.countDown();
        verify(persistence, timeout(2_000).times(1)).complete(any());
    }

    @Test
    void runningRuntimeReceivesCancellationWithinTheSameFiveSecondDeadline() throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        CountDownLatch runtimeEntered = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        AtomicReference<com.agentflow.core.cancel.CancellationSignal> signal = new AtomicReference<>();
        AgentRuntime runtime = (request, sink, options) -> {
            signal.set(options.cancellationSignal());
            runtimeEntered.countDown();
            try {
                assertThat(releaseRuntime.await(15, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        };
        RunPersistence persistence = mock(RunPersistence.class);
        when(persistence.createQueued(any())).thenAnswer(invocation -> queued(invocation.getArgument(0), now));
        when(persistence.markRunning(anyString(), any())).thenAnswer(invocation ->
                new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED,
                        running(invocation.getArgument(0), now)));
        when(persistence.complete(any())).thenAnswer(invocation -> {
            RunResultProjector.FinalProjection projection = invocation.getArgument(0);
            return new RunPersistence.CommittedTerminal(success(projection, now), true, false);
        });
        RunLifecycleProperties limits = new RunLifecycleProperties(1, 1, 2,
                Duration.ofSeconds(30), 256, 1_048_576, 16_384, 128,
                Duration.ofMinutes(10), 16, 32, 32);
        InMemoryRunEventHub hub = new InMemoryRunEventHub(new ObjectMapper(),
                256, 1_048_576, 16_384, 2, 128,
                Duration.ofMinutes(10).toNanos(), System::nanoTime);
        AtomicInteger ids = new AtomicInteger();
        RunCoordinator coordinator = new RunCoordinator((query, timeout, cancellation) -> com.agentflow.core.context.ContextSeed.empty(), runtime, persistence, hub,
                new RunEventProjector(), new RunResultProjector(),
                new BoundedRunExecutor(1, 1, Thread::new), limits,
                Clock.fixed(now, ZoneOffset.UTC), System::nanoTime,
                () -> "run-" + ids.getAndIncrement());

        coordinator.create("owner", "blocking runtime");
        assertThat(runtimeEntered.await(2, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        coordinator.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isBetween(Duration.ofMillis(4_500), Duration.ofMillis(6_000));
        assertThat(signal.get().isCancelled()).isTrue();
        assertThat(coordinator.availability()).isEqualTo(RunCoordinator.Availability.STOPPING);
        releaseRuntime.countDown();
    }

    private static RunSnapshot queued(RunPersistence.CreateCommand command, Instant now) {
        return new RunSnapshot(command.taskId(), command.taskId(), command.sessionId(),
                RunLifecycleStatus.QUEUED, command.input(), null, now, now,
                null, null, false, null, null, null, false, null);
    }

    private static RunSnapshot running(String taskId, Instant now) {
        return new RunSnapshot(taskId, taskId, "id-1", RunLifecycleStatus.RUNNING,
                "first", null, now, now, now, null,
                false, null, null, null, false, null);
    }

    private static RunSnapshot success(RunResultProjector.FinalProjection projection, Instant now) {
        return new RunSnapshot(projection.taskId(), projection.taskId(), "id-1",
                projection.status(), "first", projection.finalAnswer(), now, now, now,
                projection.finishedAt(), projection.cancelRequested(), projection.terminationReason(),
                projection.runtimeReason(), projection.errorCode(), projection.recordingComplete(),
                projection.usage());
    }
}
