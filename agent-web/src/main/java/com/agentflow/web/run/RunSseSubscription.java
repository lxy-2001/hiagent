package com.agentflow.web.run;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunSseSubscription implements Runnable {
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final RunEventHub.Subscription subscription;
    private final SseEmitter emitter;
    private final Clock clock;
    private final Instant expiresAt;
    private final Runnable onExit;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private long cursor;

    public RunSseSubscription(RunEventHub.Subscription subscription, SseEmitter emitter,
                              long cursor, Clock clock, Instant expiresAt, Runnable onExit) {
        this.subscription = Objects.requireNonNull(subscription, "subscription must not be null");
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
        if (cursor < 0) throw new IllegalArgumentException("cursor must not be negative");
        this.cursor = cursor;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.onExit = Objects.requireNonNull(onExit, "onExit must not be null");
    }

    public void ready() {
        if (!closed.get()) ready.countDown();
    }

    public void signalClose() {
        closed.set(true);
        ready.countDown();
        subscription.wake();
    }

    boolean isClosed() { return closed.get(); }

    @Override
    public void run() {
        try {
            if (!ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || closed.get()) return;
            while (!closed.get() && clock.instant().isBefore(expiresAt)) {
                Duration remaining = Duration.between(clock.instant(), expiresAt);
                Duration wait = remaining.compareTo(HEARTBEAT_INTERVAL) < 0 ? remaining : HEARTBEAT_INTERVAL;
                RunEventHub.ReadResult result = subscription.read(cursor, wait.isNegative() ? Duration.ZERO : wait);
                if (closed.get()) break;
                switch (result.status()) {
                    case FRAME -> {
                        RunEventHub.PublishedFrame frame = result.frame();
                        emitter.send(SseEmitter.event().id(frame.event().eventId())
                                .name(frame.event().type().name())
                                .data(new String(frame.jsonBytes(), StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));
                        cursor = Long.parseLong(frame.event().eventId());
                        if (frame.event().type() == RunEvent.Type.RUN_TERMINATED) {
                            closed.set(true);
                            emitter.complete();
                        }
                    }
                    case IDLE -> emitter.send(SseEmitter.event().comment("keepalive"));
                    case DONE -> { closed.set(true); emitter.complete(); }
                    case TOO_OLD, UNAVAILABLE -> { closed.set(true); emitter.complete(); }
                }
            }
            if (!closed.get()) {
                closed.set(true);
                emitter.complete();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            closed.set(true);
            try { emitter.completeWithError(failure); } catch (RuntimeException ignored) { }
        } finally {
            subscription.close();
            onExit.run();
        }
    }
}
