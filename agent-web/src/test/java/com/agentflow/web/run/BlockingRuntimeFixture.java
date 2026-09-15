package com.agentflow.web.run;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.runtime.AgentRunOptions;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class BlockingRuntimeFixture implements AgentRuntime {

    private static final Duration INTERNAL_WAIT_LIMIT = Duration.ofSeconds(5);

    private final Function<AgentRequest, AgentResult> outcome;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch returned = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();

    private BlockingRuntimeFixture(Function<AgentRequest, AgentResult> outcome) {
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    static BlockingRuntimeFixture returning(AgentResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new BlockingRuntimeFixture(request -> result);
    }

    static BlockingRuntimeFixture throwing(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        return new BlockingRuntimeFixture(request -> {
            throw failure;
        });
    }

    @Override
    public AgentResult run(AgentRequest request, AgentEventSink eventSink, AgentRunOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        Objects.requireNonNull(options, "options must not be null");
        calls.incrementAndGet();
        int activeNow = active.incrementAndGet();
        maxConcurrent.accumulateAndGet(activeNow, Math::max);
        started.countDown();
        try {
            if (!release.await(INTERNAL_WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("runtime fixture was not released within " + INTERNAL_WAIT_LIMIT);
            }
            return outcome.apply(request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("runtime fixture wait was interrupted", interrupted);
        } finally {
            active.decrementAndGet();
            returned.countDown();
        }
    }

    boolean awaitStarted(Duration timeout) throws InterruptedException {
        return await(started, timeout);
    }

    boolean awaitReturned(Duration timeout) throws InterruptedException {
        return await(returned, timeout);
    }

    void release() {
        release.countDown();
    }

    int callCount() {
        return calls.get();
    }

    int activeCount() {
        return active.get();
    }

    int maxConcurrentCount() {
        return maxConcurrent.get();
    }

    private static boolean await(CountDownLatch latch, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
