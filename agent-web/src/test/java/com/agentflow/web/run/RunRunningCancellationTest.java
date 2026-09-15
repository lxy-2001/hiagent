package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunRunningCancellationTest {
    @Test
    void runningCancellationSignalsCooperativelyButSuccessfulRuntimeResultStillWins() throws Exception {
        runCancellationRace(AgentResult.success("task-1", "done", List.of(), TokenUsage.empty()),
                RunLifecycleStatus.SUCCEEDED, RunTerminationReason.COMPLETED);
    }

    @Test
    void runningCancellationDoesNotReplaceAnActualModelFailure() throws Exception {
        runCancellationRace(AgentResult.failure("task-1", RunStatus.FAILED,
                        TerminationReason.MODEL_ERROR, "model failed", List.of(), TokenUsage.empty()),
                RunLifecycleStatus.FAILED, RunTerminationReason.MODEL_ERROR);
    }

    private void runCancellationRace(AgentResult result, RunLifecycleStatus expectedStatus,
                                     RunTerminationReason expectedReason) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<com.agentflow.core.cancel.CancellationSignal> signal = new AtomicReference<>();
        try (RunCancellationTestSupport support = new RunCancellationTestSupport((request, sink, options) -> {
            signal.set(options.cancellationSignal());
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return result;
        })) {
            support.coordinator.create("owner", "input");
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            RunCoordinator.CancelReply reply = support.coordinator.cancel("owner", "task-1");

            assertThat(reply.accepted()).isTrue();
            assertThat(signal.get().isCancelled()).isTrue();
            assertThat(support.coordinator.inFlightCount()).isOne();
            release.countDown();
            RunSnapshot terminal = awaitTerminal(support, Duration.ofSeconds(2));
            assertThat(terminal.status()).isEqualTo(expectedStatus);
            assertThat(terminal.terminationReason()).isEqualTo(expectedReason);
            assertThat(terminal.cancelRequested()).isTrue();

            RunCoordinator.CancelReply afterTerminal = support.coordinator.cancel("owner", "task-1");
            assertThat(afterTerminal.accepted()).isFalse();
            assertThat(afterTerminal.snapshot()).isEqualTo(terminal);
        } finally {
            release.countDown();
        }
    }

    static RunSnapshot awaitTerminal(RunCancellationTestSupport support, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RunSnapshot current;
        do {
            current = support.snapshots.get("task-1");
            if (current != null && current.status().isTerminal()) return current;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("run did not reach a terminal state");
    }
}
