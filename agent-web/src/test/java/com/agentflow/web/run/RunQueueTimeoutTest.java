package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunQueueTimeoutTest {
    @Test
    void maintenanceExpiresQueuedRunsAtTheMonotonicDeadlineWithoutCallingRuntime() throws Exception {
        BlockingRuntimeFixture runtime = BlockingRuntimeFixture.returning(
                AgentResult.success("task-1", "done", List.of(), TokenUsage.empty()));
        try (RunCancellationTestSupport support = new RunCancellationTestSupport(
                runtime, Duration.ofSeconds(30), 1, 3)) {
            support.coordinator.create("owner", "first");
            assertThat(runtime.awaitStarted(Duration.ofSeconds(2))).isTrue();
            support.coordinator.create("owner", "queued");

            support.time.advance(Duration.ofSeconds(29));
            support.coordinator.maintainOnce();
            assertThat(support.snapshots.get("task-2").status()).isEqualTo(RunLifecycleStatus.QUEUED);

            support.time.advance(Duration.ofSeconds(1));
            support.coordinator.maintainOnce();

            RunSnapshot expired = support.snapshots.get("task-2");
            assertThat(expired.status()).isEqualTo(RunLifecycleStatus.TIMED_OUT);
            assertThat(expired.terminationReason()).isEqualTo(RunTerminationReason.QUEUE_TIMEOUT);
            assertThat(runtime.callCount()).isOne();
            runtime.release();
        } finally {
            runtime.release();
        }
    }

    @Test
    void wallClockMovementDoesNotChangeTheQueueDeadlineDecision() {
        RunControl control = RunControl.queued("task", "owner", 10, 20);
        assertThat(control.claimStart(19)).isEqualTo(RunControl.StartClaim.START);

        RunControl exact = RunControl.queued("exact", "owner", 10, 20);
        assertThat(exact.claimStart(20)).isEqualTo(RunControl.StartClaim.EXPIRED);

        RunControl after = RunControl.queued("after", "owner", 10, 20);
        assertThat(after.claimStart(21)).isEqualTo(RunControl.StartClaim.EXPIRED);
    }
}
