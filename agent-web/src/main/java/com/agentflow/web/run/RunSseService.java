package com.agentflow.web.run;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RunSseService implements AutoCloseable {
    public static final String REQUEST_SUBSCRIPTION = RunSseService.class.getName() + ".subscription";
    private static final Duration MAX_LIFETIME = Duration.ofSeconds(60);

    public record OpenResponse(HttpStatus status, SseEmitter emitter) { }

    private final RunEventHub hub;
    private final Clock clock;
    private final ThreadPoolExecutor senders;
    private final Set<RunSseSubscription> active = ConcurrentHashMap.newKeySet();

    public RunSseService(RunEventHub hub, RunLifecycleProperties properties, Clock clock) {
        this.hub = Objects.requireNonNull(hub, "hub must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agent-sse-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.senders = new ThreadPoolExecutor(properties.senderThreads(), properties.senderThreads(),
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public OpenResponse open(String taskId, long cursor, Instant jwtExpiresAt,
                             HttpServletRequest request) {
        Objects.requireNonNull(jwtExpiresAt, "jwtExpiresAt must not be null");
        Objects.requireNonNull(request, "request must not be null");
        RunEventHub.OpenResult opened = hub.open(taskId, cursor);
        HttpStatus status = switch (opened.status()) {
            case DONE -> HttpStatus.NO_CONTENT;
            case CAPACITY -> HttpStatus.TOO_MANY_REQUESTS;
            case OPEN -> HttpStatus.OK;
            case TOO_OLD, AHEAD, UNAVAILABLE -> HttpStatus.GONE;
        };
        if (opened.status() != RunEventHub.OpenStatus.OPEN) return new OpenResponse(status, null);

        Instant lifetime = clock.instant().plus(MAX_LIFETIME);
        Instant expiresAt = jwtExpiresAt.isBefore(lifetime) ? jwtExpiresAt : lifetime;
        long timeout = Math.max(1L, Duration.between(clock.instant(), expiresAt).toMillis());
        SseEmitter emitter = new SseEmitter(timeout);
        final RunSseSubscription[] holder = new RunSseSubscription[1];
        RunSseSubscription subscription = new RunSseSubscription(opened.subscription(), emitter,
                cursor, clock, expiresAt, () -> active.remove(holder[0]));
        holder[0] = subscription;
        active.add(subscription);
        emitter.onCompletion(subscription::signalClose);
        emitter.onTimeout(subscription::signalClose);
        emitter.onError(ignored -> subscription.signalClose());
        request.setAttribute(REQUEST_SUBSCRIPTION, subscription);
        try {
            senders.execute(subscription);
            return new OpenResponse(HttpStatus.OK, emitter);
        } catch (RejectedExecutionException rejected) {
            active.remove(subscription);
            subscription.signalClose();
            opened.subscription().close();
            request.removeAttribute(REQUEST_SUBSCRIPTION);
            return new OpenResponse(HttpStatus.TOO_MANY_REQUESTS, null);
        }
    }

    public int activeCount() { return active.size(); }

    @Override
    public void close() {
        active.forEach(RunSseSubscription::signalClose);
        hub.closeSubscriptions();
        senders.shutdown();
        try { senders.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
