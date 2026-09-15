package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunQueuedCancellationTest {
    @Test
    void queuedCancellationRemovesWorkAndNeverCallsRuntimeForThatRun() throws Exception {
        BlockingRuntimeFixture runtime = BlockingRuntimeFixture.returning(
                AgentResult.success("task-1", "done", List.of(), TokenUsage.empty()));
        try (RunCancellationTestSupport support = new RunCancellationTestSupport(runtime)) {
            support.coordinator.create("owner", "first");
            assertThat(runtime.awaitStarted(Duration.ofSeconds(2))).isTrue();
            support.coordinator.create("owner", "second");

            RunCoordinator.CancelReply cancelled = support.coordinator.cancel("owner", "task-2");
            RunCoordinator.CancelReply repeated = support.coordinator.cancel("owner", "task-2");

            assertThat(cancelled.snapshot().status()).isEqualTo(RunLifecycleStatus.CANCELLED);
            assertThat(cancelled.accepted()).isFalse();
            assertThat(repeated.snapshot()).isEqualTo(cancelled.snapshot());
            assertThat(runtime.callCount()).isOne();
            runtime.release();
        }
    }

    @Test
    void removeFalseKeepsTheExecutorSlotUntilTheRunningWrapperReallyExits() throws Exception {
        BoundedRunExecutor executor = new BoundedRunExecutor(1, 1, Thread::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger exits = new AtomicInteger();
        try {
            BoundedRunExecutor.Dispatch dispatch = executor.dispatch(() -> {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, () -> { }, exits::incrementAndGet);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(executor.remove(dispatch.handle())).isFalse();
            assertThat(executor.activeCount()).isOne();
            assertThat(exits).hasValue(0);

            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(Duration.ofSeconds(2))).isTrue();
            assertThat(exits).hasValue(1);
        } finally {
            release.countDown();
            executor.close();
        }
    }
}
