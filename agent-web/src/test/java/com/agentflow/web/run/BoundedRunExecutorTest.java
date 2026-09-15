package com.agentflow.web.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedRunExecutorTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    @Test
    void usesFourWorkersAndThirtyTwoPhysicalQueueSlotsThenRejectsThirtySeventh() throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(36);
        AtomicInteger rejected = new AtomicInteger();
        BoundedRunExecutor executor = new BoundedRunExecutor(4, 32, namedFactory(new AtomicInteger()));
        try {
            List<BoundedRunExecutor.Dispatch> accepted = new ArrayList<>();
            for (int index = 0; index < 36; index++) {
                accepted.add(executor.dispatch(() -> {
                    workersStarted.countDown();
                    await(release);
                }, rejected::incrementAndGet, exited::countDown));
            }
            assertThat(workersStarted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            BoundedRunExecutor.Dispatch overflow = executor.dispatch(
                    () -> { }, rejected::incrementAndGet, () -> { });

            assertThat(accepted).allMatch(BoundedRunExecutor.Dispatch::accepted);
            assertThat(overflow.accepted()).isFalse();
            assertThat(overflow.handle()).isNull();
            assertThat(rejected).hasValue(1);
            assertThat(executor.poolSize()).isEqualTo(4);
            assertThat(executor.activeCount()).isEqualTo(4);
            assertThat(executor.queueSize()).isEqualTo(32);

            release.countDown();
            assertThat(exited.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(WAIT)).isTrue();
        }
    }

    @Test
    void failedRemovalKeepsCompletionOwnedByActuallyRunningTask() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch runningExited = new CountDownLatch(1);
        AtomicInteger queuedDetached = new AtomicInteger();
        BoundedRunExecutor executor = new BoundedRunExecutor(1, 1, namedFactory(new AtomicInteger()));
        try {
            BoundedRunExecutor.Dispatch active = executor.dispatch(() -> {
                running.countDown();
                await(release);
            }, () -> { }, runningExited::countDown);
            assertThat(running.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            BoundedRunExecutor.Dispatch queued = executor.dispatch(
                    () -> { }, () -> { }, queuedDetached::incrementAndGet);

            assertThat(executor.remove(active.handle())).isFalse();
            assertThat(runningExited.getCount()).isEqualTo(1);
            assertThat(executor.remove(queued.handle())).isTrue();
            assertThat(queuedDetached).hasValue(1);

            release.countDown();
            assertThat(runningExited.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(WAIT)).isTrue();
        }
    }

    @Test
    void dispatchAfterShutdownIsRejectedWithoutCreatingAnotherThread() throws Exception {
        AtomicInteger threadsCreated = new AtomicInteger();
        CountDownLatch firstExited = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        BoundedRunExecutor executor = new BoundedRunExecutor(1, 1, namedFactory(threadsCreated));
        executor.dispatch(() -> { }, rejected::incrementAndGet, firstExited::countDown);
        assertThat(firstExited.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(WAIT)).isTrue();
        int threadCountAtShutdown = threadsCreated.get();

        BoundedRunExecutor.Dispatch dispatch = executor.dispatch(
                () -> { }, rejected::incrementAndGet, () -> { });

        assertThat(dispatch.accepted()).isFalse();
        assertThat(rejected).hasValue(1);
        assertThat(threadsCreated).hasValue(threadCountAtShutdown);
    }

    private ThreadFactory namedFactory(AtomicInteger threadsCreated) {
        return runnable -> {
            Thread thread = new Thread(runnable, "run-worker-" + threadsCreated.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("test latch was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch wait interrupted", interrupted);
        }
    }
}
