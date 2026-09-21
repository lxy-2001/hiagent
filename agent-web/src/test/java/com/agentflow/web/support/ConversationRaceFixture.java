package com.agentflow.web.support;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Independent, one-shot test gates; transaction semantics remain in CommitFaultFixture. */
public final class ConversationRaceFixture implements AutoCloseable {
    public enum Boundary { PREPARATION, COMMIT, WORKER_EXIT }

    private final Map<Boundary, Gate> gates = new EnumMap<>(Boundary.class);
    private final Map<Boundary, RuntimeException> failures;
    private final long waitNanos;

    public ConversationRaceFixture() {
        this(Duration.ofSeconds(5), Map.of());
    }

    public ConversationRaceFixture(Duration waitLimit, Map<Boundary, RuntimeException> failures) {
        this.waitNanos = nonNegativeNanos(waitLimit);
        if (waitNanos == 0) {
            throw new IllegalArgumentException("waitLimit must be positive");
        }
        this.failures = Map.copyOf(failures);
        for (Boundary boundary : Boundary.values()) {
            gates.put(boundary, new Gate());
        }
    }

    public void reach(Boundary boundary) {
        Gate gate = gate(boundary);
        gate.reached.countDown();
        try {
            if (!gate.release.await(waitNanos, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(boundary + " fixture was not released within its wait limit");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(boundary + " fixture wait was interrupted", interrupted);
        }
        RuntimeException failure = failures.get(boundary);
        if (failure != null) {
            throw failure;
        }
    }

    public boolean awaitReached(Boundary boundary, Duration timeout) throws InterruptedException {
        return gate(boundary).reached.await(nonNegativeNanos(timeout), TimeUnit.NANOSECONDS);
    }

    public void release(Boundary boundary) {
        gate(boundary).release.countDown();
    }

    @Override
    public void close() {
        gates.values().forEach(gate -> gate.release.countDown());
    }

    private Gate gate(Boundary boundary) {
        return gates.get(Objects.requireNonNull(boundary, "boundary must not be null"));
    }

    private static long nonNegativeNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        return duration.toNanos();
    }

    private static final class Gate {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }
}
