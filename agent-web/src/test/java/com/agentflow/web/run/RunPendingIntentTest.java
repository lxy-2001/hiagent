package com.agentflow.web.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RunPendingIntentTest {

    @Test
    void oldCancellationCallbackCannotClearNewerFrozenFinalIntent() {
        RunControl control = control();
        long cancelRevision = control.markCancellationPending();
        RunControl.PendingClaim cancelClaim = control.claimPending().orElseThrow();

        long finalRevision = control.freezeFinal("final-projection");

        assertThat(finalRevision).isEqualTo(cancelRevision + 1);
        assertThat(control.claimPending()).isEmpty();
        assertThat(control.finishPending(cancelClaim, RunControl.PendingOutcome.CONFIRMED)).isTrue();
        assertThat(control.pending()).contains(new RunControl.PendingView(
                RunControl.PendingKind.FINAL_PENDING, finalRevision, "final-projection"));

        RunControl.PendingClaim finalClaim = control.claimPending().orElseThrow();
        assertThat(control.finishPending(finalClaim, RunControl.PendingOutcome.TERMINAL_CONFIRMED)).isFalse();
        assertThat(control.pending()).isEmpty();
    }

    @Test
    void allowsAtMostOneIoClaimForTheRun() throws Exception {
        RunControl control = control();
        control.markCancellationPending();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<RunControl.PendingClaim>> claim = control::claimPending;
            List<Future<Optional<RunControl.PendingClaim>>> futures = executor.invokeAll(List.of(claim, claim));

            assertThat(futures.stream().map(this::get).filter(Optional::isPresent)).hasSize(1);
            assertThat(futures.stream().map(this::get).filter(Optional::isEmpty)).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void freezesFinalProjectionOnlyOnceAndIgnoresLateCancellationReplacement() {
        RunControl control = control();

        long frozenRevision = control.freezeFinal("first");
        assertThat(control.freezeFinal("second")).isEqualTo(frozenRevision);
        assertThat(control.markCancellationPending()).isEqualTo(frozenRevision);

        assertThat(control.pending()).contains(new RunControl.PendingView(
                RunControl.PendingKind.FINAL_PENDING, frozenRevision, "first"));
    }

    @Test
    void releasesOnlyOnceAfterTerminalConfirmationAndWorkerExit() {
        RunControl control = control();
        control.markWorkerEntered();
        control.markTerminalConfirmed();

        assertThat(control.claimRelease()).isFalse();

        control.markWorkerExited();
        assertThat(control.claimRelease()).isTrue();
        assertThat(control.claimRelease()).isFalse();
        assertThat(control.phase()).isEqualTo(RunControl.Phase.RELEASED);
    }

    @Test
    void detachedQueuedRunnableCanReleaseAfterTerminalConfirmation() {
        RunControl control = control();
        control.markQueueDetached();
        control.markTerminalConfirmed();

        assertThat(control.claimRelease()).isTrue();
        assertThat(control.claimRelease()).isFalse();
    }

    private Optional<RunControl.PendingClaim> get(Future<Optional<RunControl.PendingClaim>> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private RunControl control() {
        return RunControl.queued("run-1", "owner-1", 1_000L, 2_000L);
    }
}
