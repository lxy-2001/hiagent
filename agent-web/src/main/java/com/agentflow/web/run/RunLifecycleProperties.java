package com.agentflow.web.run;

import java.time.Duration;
import java.util.Objects;

public record RunLifecycleProperties(
        int workerThreads,
        int queueCapacity,
        int inFlightCapacity,
        Duration queueTimeout,
        int eventWindowCount,
        int eventWindowBytes,
        int eventFrameBytes,
        int terminalCacheCapacity,
        Duration terminalCacheTtl,
        int subscriptionsPerRun,
        int globalSubscriptions,
        int senderThreads
) {
    public static final int MAX_WORKER_THREADS = 4;
    public static final int MAX_QUEUE_CAPACITY = 32;
    public static final int MAX_IN_FLIGHT_CAPACITY = 36;
    public static final Duration MAX_QUEUE_TIMEOUT = Duration.ofSeconds(30);
    public static final int MAX_EVENT_WINDOW_COUNT = 256;
    public static final int MAX_EVENT_WINDOW_BYTES = 1024 * 1024;
    public static final int MAX_EVENT_FRAME_BYTES = 16 * 1024;
    public static final int MAX_TERMINAL_CACHE_CAPACITY = 128;
    public static final Duration MAX_TERMINAL_CACHE_TTL = Duration.ofMinutes(10);
    public static final int MAX_SUBSCRIPTIONS_PER_RUN = 16;
    public static final int MAX_GLOBAL_SUBSCRIPTIONS = 32;
    public static final int MAX_SENDER_THREADS = 32;

    public RunLifecycleProperties {
        requirePositiveAtMost(workerThreads, MAX_WORKER_THREADS, "workerThreads");
        requirePositiveAtMost(queueCapacity, MAX_QUEUE_CAPACITY, "queueCapacity");
        requirePositiveAtMost(inFlightCapacity, MAX_IN_FLIGHT_CAPACITY, "inFlightCapacity");
        requirePositiveAtMost(eventWindowCount, MAX_EVENT_WINDOW_COUNT, "eventWindowCount");
        requirePositiveAtMost(eventWindowBytes, MAX_EVENT_WINDOW_BYTES, "eventWindowBytes");
        requirePositiveAtMost(eventFrameBytes, MAX_EVENT_FRAME_BYTES, "eventFrameBytes");
        requirePositiveAtMost(terminalCacheCapacity, MAX_TERMINAL_CACHE_CAPACITY,
                "terminalCacheCapacity");
        requirePositiveAtMost(subscriptionsPerRun, MAX_SUBSCRIPTIONS_PER_RUN,
                "subscriptionsPerRun");
        requirePositiveAtMost(globalSubscriptions, MAX_GLOBAL_SUBSCRIPTIONS,
                "globalSubscriptions");
        requirePositiveAtMost(senderThreads, MAX_SENDER_THREADS, "senderThreads");
        requirePositiveAtMost(queueTimeout, MAX_QUEUE_TIMEOUT, "queueTimeout");
        requirePositiveAtMost(terminalCacheTtl, MAX_TERMINAL_CACHE_TTL, "terminalCacheTtl");

        if (inFlightCapacity != workerThreads + queueCapacity) {
            throw new IllegalArgumentException("inFlightCapacity must equal workerThreads + queueCapacity");
        }
        if (eventFrameBytes > eventWindowBytes) {
            throw new IllegalArgumentException("eventFrameBytes must not exceed eventWindowBytes");
        }
        if (subscriptionsPerRun > globalSubscriptions) {
            throw new IllegalArgumentException("subscriptionsPerRun must not exceed globalSubscriptions");
        }
        if (globalSubscriptions > senderThreads) {
            throw new IllegalArgumentException("globalSubscriptions must not exceed senderThreads");
        }
    }

    public static RunLifecycleProperties defaults() {
        return new RunLifecycleProperties(MAX_WORKER_THREADS, MAX_QUEUE_CAPACITY,
                MAX_IN_FLIGHT_CAPACITY, MAX_QUEUE_TIMEOUT, MAX_EVENT_WINDOW_COUNT,
                MAX_EVENT_WINDOW_BYTES, MAX_EVENT_FRAME_BYTES, MAX_TERMINAL_CACHE_CAPACITY,
                MAX_TERMINAL_CACHE_TTL, MAX_SUBSCRIPTIONS_PER_RUN,
                MAX_GLOBAL_SUBSCRIPTIONS, MAX_SENDER_THREADS);
    }

    private static void requirePositiveAtMost(int value, int maximum, String field) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maximum);
        }
    }

    private static void requirePositiveAtMost(Duration value, Duration maximum, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " must be positive and at most " + maximum);
        }
    }
}
