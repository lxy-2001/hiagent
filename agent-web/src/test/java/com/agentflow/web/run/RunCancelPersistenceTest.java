package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

class RunCancelPersistenceTest {
    @Test
    void failedFlagWriteReturnsUnavailableWithoutUndoingTheRuntimeSignal() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<com.agentflow.core.cancel.CancellationSignal> signal = new AtomicReference<>();
        try (RunCancellationTestSupport support = new RunCancellationTestSupport((request, sink, options) -> {
            signal.set(options.cancellationSignal());
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return AgentResult.success(request.taskId(), "done", List.of(), TokenUsage.empty());
        })) {
            support.coordinator.create("owner", "input");
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            doThrow(new IllegalStateException("database unavailable"))
                    .when(support.persistence).requestCancellation(anyString(), any());

            assertThatThrownBy(() -> support.coordinator.cancel("owner", "task-1"))
                    .isInstanceOf(RunCoordinator.RunUnavailableException.class);
            assertThat(signal.get().isCancelled()).isTrue();
            assertThat(support.coordinator.control("task-1").orElseThrow().pending().orElseThrow().kind())
                    .isEqualTo(RunControl.PendingKind.CANCEL_FLAG_PENDING);
            release.countDown();
            RunSnapshot terminal = RunRunningCancellationTest.awaitTerminal(support, Duration.ofSeconds(2));
            assertThat(terminal.status()).isEqualTo(RunLifecycleStatus.SUCCEEDED);
            assertThat(terminal.cancelRequested()).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void finalProjectionSupersedesAnInFlightCancelFlagRevision() {
        RunControl control = RunControl.queued("task", "owner", 0, 10);
        assertThat(control.claimStart(1)).isEqualTo(RunControl.StartClaim.START);
        assertThat(control.claimRuntimeCall(true)).isEqualTo(RunControl.RuntimeCallClaim.CALL);
        assertThat(control.requestCancel()).isEqualTo(RunControl.CancelClaim.SIGNALLED);
        control.markCancellationPending();
        RunControl.PendingClaim oldClaim = control.claimPending().orElseThrow();

        Object finalProjection = new Object();
        control.freezeFinal(finalProjection);
        control.finishPending(oldClaim, RunControl.PendingOutcome.CONFIRMED);

        RunControl.PendingView pending = control.pending().orElseThrow();
        assertThat(pending.kind()).isEqualTo(RunControl.PendingKind.FINAL_PENDING);
        assertThat(pending.payload()).isSameAs(finalProjection);
        assertThat(pending.revision()).isGreaterThan(oldClaim.revision());
    }
}
