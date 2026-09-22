package com.agentflow.web.conversation;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.*;
import com.agentflow.core.runtime.*;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextPreparationTest {
    @Test
    void preparationIsReadOnceAndDeductedFromRunDuration() {
        exercise("success", 1, "COMPLETED");
    }
    @Test
    void preparationCancellationTimeoutFailureAndLateSnapshotNeverInvokeRuntime() {
        exercise("cancel", 0, "CANCELLED");
        exercise("cancel-timeout", 31, "CANCELLED");
        exercise("timeout", 31, "TIMED_OUT");
        exercise("failure", 0, "CONTEXT_SOURCE_UNAVAILABLE");
        exercise("late", 3, "CONTEXT_SOURCE_UNAVAILABLE");
    }

    private void exercise(String mode, int seconds, String expectedReason) {
        var tick = new AtomicLong();
        var reads = new AtomicInteger();
        var calls = new AtomicInteger();
        var finalFact = new AtomicReference<RunResultProjector.FinalProjection>();
        var seed = new ContextSeed(1, List.of(new ConversationTurn("old", 1, "q", "a")), List.of(), false, 0);
        ContextSource source = (query, timeout, cancellation) -> {
            reads.incrementAndGet();
            assertThat(query.currentRunId()).isEqualTo("run");
            assertThat(timeout).isEqualTo(Duration.ofSeconds(2));
            tick.addAndGet(Duration.ofSeconds(seconds).toNanos());
            if (mode.startsWith("cancel")) ((RunControl) cancellation).requestCancel();
            if (mode.equals("failure")) throw new ContextSource.ContextSourceException("unavailable");
            return seed;
        };
        AgentRuntime runtime = (request, sink, options) -> {
            calls.incrementAndGet();
            assertThat(request.contextSeed()).isSameAs(seed);
            assertThat(options.budget().maxDuration()).isEqualTo(Duration.ofSeconds(29));
            return AgentResult.success("run", "done", List.of(), TokenUsage.empty());
        };
        var persistence = mock(RunPersistence.class);
        var now = Instant.parse("2026-09-22T00:00:00Z");
        var queued = new RunSnapshot("run", "run", "session", RunLifecycleStatus.QUEUED, "input", null, now, now,
                null, null, false, null, null, null, false, null);
        when(persistence.createQueued(any())).thenReturn(queued);
        when(persistence.markRunning(anyString(), any())).thenReturn(new RunPersistence.StartResult(RunPersistence.StartOutcome.STARTED, queued));
        when(persistence.complete(any())).thenAnswer(invocation -> {
            RunResultProjector.FinalProjection p = invocation.getArgument(0);
            finalFact.set(p);
            return new RunPersistence.CommittedTerminal(new RunSnapshot("run", "run", "session", p.status(), "input", p.finalAnswer(), now, now,
                    now, now, p.cancelRequested(), p.terminationReason(), p.runtimeReason(), p.errorCode(), p.recordingComplete(), p.usage()), true, false);
        });
        var executor = mock(BoundedRunExecutor.class);
        when(executor.dispatch(any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            invocation.<Runnable>getArgument(2).run();
            return new BoundedRunExecutor.Dispatch(false, null);
        });
        var ids = new AtomicInteger();
        var hub = new InMemoryRunEventHub(new ObjectMapper(), 256, 1048576, 16384, 36, 128, Duration.ofMinutes(10).toNanos(), tick::get, 16, 32);
        try (var coordinator = new RunCoordinator(source, runtime, persistence, hub, new RunEventProjector(), new RunResultProjector(),
                executor, RunLifecycleProperties.defaults(), Clock.fixed(now, ZoneOffset.UTC), tick::get,
                () -> ids.getAndIncrement() == 0 ? "run" : "session")) {
            coordinator.create("owner", "input");
            assertThat(reads.get()).isOne();
            assertThat(calls.get()).isEqualTo(mode.equals("success") ? 1 : 0);
            assertThat(finalFact.get().terminationReason().name()).isEqualTo(expectedReason);
            assertThat(finalFact.get().status().name()).isEqualTo(switch (expectedReason) {
                case "COMPLETED" -> "SUCCEEDED";
                case "CANCELLED" -> "CANCELLED";
                case "TIMED_OUT" -> "TIMED_OUT";
                default -> "FAILED";
            });
        }
    }
}
