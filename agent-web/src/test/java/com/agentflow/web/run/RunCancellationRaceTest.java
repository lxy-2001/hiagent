package com.agentflow.web.run;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunCancellationRaceTest {
    @Test
    void oneHundredDeterministicInterleavingsKeepOneRuntimeClaimOneTerminalAndOneRelease() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                RunControl control = RunControl.queued("task-" + iteration, "owner", 0, 100);
                AtomicReference<RunControl.StartClaim> start = new AtomicReference<>();
                AtomicReference<RunControl.CancelClaim> cancel = new AtomicReference<>();
                if (iteration % 3 == 0) {
                    cancel.set(control.requestCancel());
                    start.set(control.claimStart(1));
                } else if (iteration % 3 == 1) {
                    start.set(control.claimStart(1));
                    cancel.set(control.requestCancel());
                } else {
                    CountDownLatch release = new CountDownLatch(1);
                    Future<?> starting = racers.submit(() -> { await(release); start.set(control.claimStart(1)); });
                    Future<?> cancelling = racers.submit(() -> { await(release); cancel.set(control.requestCancel()); });
                    release.countDown();
                    starting.get();
                    cancelling.get();
                }

                int runtimeClaims = 0;
                if (start.get() == RunControl.StartClaim.START
                        && control.claimRuntimeCall(true) == RunControl.RuntimeCallClaim.CALL) {
                    runtimeClaims++;
                }
                assertThat(runtimeClaims).isLessThanOrEqualTo(1);
                assertThat(cancel.get()).isNotNull();

                Object winner = new Object();
                control.freezeFinal(winner);
                control.freezeFinal(new Object());
                assertThat(control.pending().orElseThrow().payload()).isSameAs(winner);
                RunControl.PendingClaim terminal = control.claimPending().orElseThrow();
                control.finishPending(terminal, RunControl.PendingOutcome.TERMINAL_CONFIRMED);
                control.markExecutorSlotReleased();
                assertThat(control.claimRelease()).isTrue();
                assertThat(control.claimRelease()).isFalse();
            }
        } finally {
            racers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
