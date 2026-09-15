package com.agentflow.web.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RunControlTest {

    @Test
    void startsBeforeDeadlineAndExpiresAtDeadline() {
        RunControl beforeDeadline = control();
        RunControl atDeadline = control();

        assertThat(beforeDeadline.claimStart(1_999L)).isEqualTo(RunControl.StartClaim.START);
        assertThat(beforeDeadline.claimStart(1_999L)).isEqualTo(RunControl.StartClaim.SKIP);
        assertThat(atDeadline.claimStart(2_000L)).isEqualTo(RunControl.StartClaim.EXPIRED);
        assertThat(atDeadline.claimStart(2_001L)).isEqualTo(RunControl.StartClaim.SKIP);
    }

    @Test
    void grantsStartToOnlyOneConcurrentWorker() throws Exception {
        RunControl control = control();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<RunControl.StartClaim> claim = () -> control.claimStart(1_500L);
            List<Future<RunControl.StartClaim>> futures = executor.invokeAll(List.of(claim, claim));

            assertThat(futures)
                    .extracting(future -> future.get(2, TimeUnit.SECONDS))
                    .containsExactlyInAnyOrder(RunControl.StartClaim.START, RunControl.StartClaim.SKIP);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cannotClaimRuntimeCallBeforeStartCommitIsConfirmed() {
        RunControl control = control();
        assertThat(control.claimStart(1_500L)).isEqualTo(RunControl.StartClaim.START);

        assertThat(control.claimRuntimeCall(false)).isEqualTo(RunControl.RuntimeCallClaim.SKIP);
        assertThat(control.runtimeCallClaimed()).isFalse();
        assertThat(control.phase()).isEqualTo(RunControl.Phase.STARTING);
    }

    @Test
    void cancellationWinsBeforeRuntimeCallAuthority() {
        RunControl control = control();
        assertThat(control.claimStart(1_500L)).isEqualTo(RunControl.StartClaim.START);

        assertThat(control.requestCancel()).isEqualTo(RunControl.CancelClaim.SIGNALLED);
        assertThat(control.claimRuntimeCall(true)).isEqualTo(RunControl.RuntimeCallClaim.CANCEL_BEFORE_CALL);

        assertThat(control.isCancelled()).isTrue();
        assertThat(control.runtimeCallClaimed()).isFalse();
        assertThat(control.claimRuntimeCall(true)).isEqualTo(RunControl.RuntimeCallClaim.SKIP);
    }

    @Test
    void runtimeCallAuthorityCanMoveFromFalseToTrueOnlyOnce() {
        RunControl control = control();
        assertThat(control.claimStart(1_500L)).isEqualTo(RunControl.StartClaim.START);

        assertThat(control.claimRuntimeCall(true)).isEqualTo(RunControl.RuntimeCallClaim.CALL);
        assertThat(control.claimRuntimeCall(true)).isEqualTo(RunControl.RuntimeCallClaim.SKIP);
        assertThat(control.runtimeCallClaimed()).isTrue();
        assertThat(control.phase()).isEqualTo(RunControl.Phase.EXECUTING);

        assertThat(control.requestCancel()).isEqualTo(RunControl.CancelClaim.SIGNALLED);
        assertThat(control.isCancelled()).isTrue();
        assertThat(control.runtimeCallClaimed()).isTrue();
    }

    @Test
    void queuedCancellationPreventsWorkerStart() {
        RunControl control = control();

        assertThat(control.requestCancel()).isEqualTo(RunControl.CancelClaim.CANCEL_BEFORE_START);
        assertThat(control.claimStart(1_500L)).isEqualTo(RunControl.StartClaim.SKIP);
        assertThat(control.phase()).isEqualTo(RunControl.Phase.FINALIZING);
    }

    private RunControl control() {
        return RunControl.queued("run-1", "owner-1", 1_000L, 2_000L);
    }
}
