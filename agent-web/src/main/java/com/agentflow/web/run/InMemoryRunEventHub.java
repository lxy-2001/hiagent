package com.agentflow.web.run;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryRunEventHub implements RunEventHub {

    private final ObjectMapper objectMapper;
    private final int windowCount;
    private final int windowBytes;
    private final int frameBytes;
    private final long initialLastEventId;
    private final int activeCapacity;
    private final int terminalCapacity;
    private final long terminalTtlNanos;
    private final LongSupplier ticker;
    private final int perRunSubscriptionCapacity;
    private final int globalSubscriptionCapacity;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<String, StreamState> terminalOrder = new LinkedHashMap<>();
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    private int activeRuns;
    private int activeSubscriptions;

    public InMemoryRunEventHub(ObjectMapper objectMapper, int windowCount, int windowBytes,
                               int frameBytes) {
        this(objectMapper, windowCount, windowBytes, frameBytes, 0);
    }

    InMemoryRunEventHub(ObjectMapper objectMapper, int windowCount, int windowBytes,
                        int frameBytes, long initialLastEventId) {
        this(objectMapper, windowCount, windowBytes, frameBytes, initialLastEventId,
                RunLifecycleProperties.MAX_IN_FLIGHT_CAPACITY,
                RunLifecycleProperties.MAX_TERMINAL_CACHE_CAPACITY,
                RunLifecycleProperties.MAX_TERMINAL_CACHE_TTL.toNanos(), System::nanoTime,
                RunLifecycleProperties.MAX_SUBSCRIPTIONS_PER_RUN,
                RunLifecycleProperties.MAX_GLOBAL_SUBSCRIPTIONS);
    }

    private InMemoryRunEventHub(ObjectMapper objectMapper, int windowCount, int windowBytes,
                                int frameBytes, long initialLastEventId, int activeCapacity,
                                int terminalCapacity, long terminalTtlNanos, LongSupplier ticker,
                                int perRunSubscriptionCapacity, int globalSubscriptionCapacity) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (windowCount <= 0 || windowBytes <= 0 || frameBytes <= 0
                || frameBytes > windowBytes || initialLastEventId < 0 || activeCapacity <= 0
                || terminalCapacity <= 0 || terminalTtlNanos <= 0) {
            throw new IllegalArgumentException("invalid event window limits");
        }
        this.windowCount = windowCount;
        this.windowBytes = windowBytes;
        this.frameBytes = frameBytes;
        this.initialLastEventId = initialLastEventId;
        this.activeCapacity = activeCapacity;
        this.terminalCapacity = terminalCapacity;
        this.terminalTtlNanos = terminalTtlNanos;
        this.ticker = Objects.requireNonNull(ticker, "ticker must not be null");
        if (perRunSubscriptionCapacity <= 0 || globalSubscriptionCapacity <= 0
                || perRunSubscriptionCapacity > globalSubscriptionCapacity) {
            throw new IllegalArgumentException("invalid subscription limits");
        }
        this.perRunSubscriptionCapacity = perRunSubscriptionCapacity;
        this.globalSubscriptionCapacity = globalSubscriptionCapacity;
    }

    InMemoryRunEventHub(ObjectMapper objectMapper, int windowCount, int windowBytes,
                        int frameBytes, int activeCapacity, int terminalCapacity,
                        long terminalTtlNanos, LongSupplier ticker) {
        this(objectMapper, windowCount, windowBytes, frameBytes, 0, activeCapacity,
                terminalCapacity, terminalTtlNanos, ticker,
                RunLifecycleProperties.MAX_SUBSCRIPTIONS_PER_RUN,
                RunLifecycleProperties.MAX_GLOBAL_SUBSCRIPTIONS);
    }

    public InMemoryRunEventHub(ObjectMapper objectMapper, int windowCount, int windowBytes,
                               int frameBytes, int activeCapacity, int terminalCapacity,
                               long terminalTtlNanos, LongSupplier ticker,
                               int perRunSubscriptionCapacity, int globalSubscriptionCapacity) {
        this(objectMapper, windowCount, windowBytes, frameBytes, 0, activeCapacity,
                terminalCapacity, terminalTtlNanos, ticker, perRunSubscriptionCapacity,
                globalSubscriptionCapacity);
    }

    public boolean markTerminal(String taskId) {
        requireTaskId(taskId);
        synchronized (cacheLock) {
            StreamState state = streams.get(taskId);
            if (state == null) {
                return false;
            }
            synchronized (state) {
                if (state.unavailable) {
                    return false;
                }
                if (state.terminal) {
                    return true;
                }
                state.terminal = true;
                state.terminalTick = ticker.getAsLong();
                activeRuns--;
                terminalOrder.put(taskId, state);
                state.notifyAll();
            }
            evictTerminalOverflow();
            return true;
        }
    }

    @Override
    public void maintain() {
        long now = ticker.getAsLong();
        synchronized (cacheLock) {
            Iterator<Map.Entry<String, StreamState>> iterator = terminalOrder.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, StreamState> entry = iterator.next();
                StreamState state = entry.getValue();
                if (now - state.terminalTick >= terminalTtlNanos) {
                    iterator.remove();
                    evict(entry.getKey(), state);
                }
            }
        }
    }

    public int activeCount() {
        synchronized (cacheLock) {
            return activeRuns;
        }
    }

    public int terminalCount() {
        synchronized (cacheLock) {
            return terminalOrder.size();
        }
    }

    public int cachedRunCount() {
        return streams.size();
    }

    @Override
    public boolean create(String taskId) {
        requireTaskId(taskId);
        synchronized (cacheLock) {
            if (streams.containsKey(taskId) || activeRuns >= activeCapacity) {
                return false;
            }
            streams.put(taskId, new StreamState(initialLastEventId));
            activeRuns++;
            return true;
        }
    }

    @Override
    public PublishResult publish(String taskId, RunEvent.Draft event) {
        requireTaskId(taskId);
        Objects.requireNonNull(event, "event must not be null");
        StreamState state = streams.get(taskId);
        if (state == null) {
            return new PublishResult(PublishStatus.MISSING, null);
        }
        synchronized (state) {
            if (state.unavailable) {
                return new PublishResult(PublishStatus.UNAVAILABLE, null);
            }
            if (state.terminal) {
                return new PublishResult(PublishStatus.TERMINAL, null);
            }
            if (!taskId.equals(event.taskId()) || !taskId.equals(event.runId())) {
                throw new IllegalArgumentException("event belongs to another run");
            }
            if (state.lastEventId == Long.MAX_VALUE) {
                state.makeUnavailable();
                return new PublishResult(PublishStatus.UNAVAILABLE, null);
            }
            long eventId = state.lastEventId + 1;
            try {
                RunEvent sequenced = event.sequence(eventId);
                byte[] json = objectMapper.writeValueAsBytes(sequenced);
                byte[] sse = encodeSse(sequenced, json);
                if (sse.length > frameBytes) {
                    state.makeUnavailable();
                    return new PublishResult(PublishStatus.UNAVAILABLE, null);
                }
                PublishedFrame frame = new PublishedFrame(sequenced, json, sse);
                state.lastEventId = eventId;
                state.frames.addLast(frame);
                state.retainedBytes = Math.addExact(state.retainedBytes, sse.length);
                while (state.frames.size() > windowCount || state.retainedBytes > windowBytes) {
                    PublishedFrame removed = state.frames.removeFirst();
                    state.retainedBytes -= removed.sseBytes().length;
                }
                state.notifyAll();
                return new PublishResult(PublishStatus.PUBLISHED, frame);
            } catch (RuntimeException exception) {
                state.makeUnavailable();
                return new PublishResult(PublishStatus.UNAVAILABLE, null);
            }
        }
    }

    @Override
    public ReplayResult replay(String taskId, long lastEventId) {
        requireTaskId(taskId);
        if (lastEventId < 0) {
            throw new IllegalArgumentException("lastEventId must not be negative");
        }
        StreamState state = streams.get(taskId);
        if (state == null) {
            return unavailableReplay();
        }
        synchronized (state) {
            if (state.unavailable) {
                return unavailableReplay();
            }
            long latest = state.lastEventId;
            long earliest = state.frames.isEmpty()
                    ? latest + 1 : Long.parseLong(state.frames.getFirst().event().eventId());
            if (lastEventId > latest) {
                return new ReplayResult(ReplayStatus.AHEAD, earliest, latest, java.util.List.of());
            }
            if (lastEventId < earliest - 1) {
                return new ReplayResult(ReplayStatus.TOO_OLD, earliest, latest, java.util.List.of());
            }
            var frames = new ArrayList<PublishedFrame>();
            for (PublishedFrame frame : state.frames) {
                if (Long.parseLong(frame.event().eventId()) > lastEventId) {
                    frames.add(frame);
                }
            }
            return new ReplayResult(ReplayStatus.AVAILABLE, earliest, latest, frames);
        }
    }

    @Override
    public OpenResult open(String taskId, long lastEventId) {
        requireTaskId(taskId);
        if (lastEventId < 0) throw new IllegalArgumentException("lastEventId must not be negative");
        synchronized (cacheLock) {
            StreamState state = streams.get(taskId);
            if (state == null) return new OpenResult(OpenStatus.UNAVAILABLE, null);
            synchronized (state) {
                Bounds bounds = bounds(state);
                if (state.unavailable) return new OpenResult(OpenStatus.UNAVAILABLE, null);
                if (lastEventId > bounds.latest()) return new OpenResult(OpenStatus.AHEAD, null);
                if (lastEventId < bounds.earliest() - 1) return new OpenResult(OpenStatus.TOO_OLD, null);
                if (state.terminal && lastEventId == bounds.latest()) {
                    return new OpenResult(OpenStatus.DONE, null);
                }
                if (state.subscriptions >= perRunSubscriptionCapacity
                        || activeSubscriptions >= globalSubscriptionCapacity) {
                    return new OpenResult(OpenStatus.CAPACITY, null);
                }
                state.subscriptions++;
                activeSubscriptions++;
                return new OpenResult(OpenStatus.OPEN, new MemorySubscription(state));
            }
        }
    }

    @Override
    public void closeSubscriptions() {
        synchronized (cacheLock) {
            for (StreamState state : streams.values()) {
                synchronized (state) {
                    state.makeUnavailable();
                }
            }
        }
    }

    public int subscriptionCount() {
        synchronized (cacheLock) { return activeSubscriptions; }
    }

    private static byte[] encodeSse(RunEvent event, byte[] json) {
        byte[] prefix = ("id: " + event.eventId() + "\nevent: " + event.type()
                + "\ndata: ").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[prefix.length + json.length + suffix.length];
        System.arraycopy(prefix, 0, encoded, 0, prefix.length);
        System.arraycopy(json, 0, encoded, prefix.length, json.length);
        System.arraycopy(suffix, 0, encoded, prefix.length + json.length, suffix.length);
        return encoded;
    }

    private static ReplayResult unavailableReplay() {
        return new ReplayResult(ReplayStatus.UNAVAILABLE, 0, 0, java.util.List.of());
    }

    private static Bounds bounds(StreamState state) {
        long latest = state.lastEventId;
        long earliest = state.frames.isEmpty() ? latest + 1
                : Long.parseLong(state.frames.getFirst().event().eventId());
        return new Bounds(earliest, latest);
    }

    private record Bounds(long earliest, long latest) { }

    private final class MemorySubscription implements Subscription {
        private final StreamState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private MemorySubscription(StreamState state) { this.state = state; }

        @Override
        public ReadResult read(long cursor, Duration maximumWait) throws InterruptedException {
            if (cursor < 0) throw new IllegalArgumentException("cursor must not be negative");
            Objects.requireNonNull(maximumWait, "maximumWait must not be null");
            if (maximumWait.isNegative()) throw new IllegalArgumentException("maximumWait must not be negative");
            synchronized (state) {
                ReadResult immediate = readNow(cursor);
                if (immediate.status() != ReadStatus.IDLE || maximumWait.isZero()) return immediate;
                long millis = maximumWait.toMillis();
                int nanos = maximumWait.minusMillis(millis).getNano();
                state.wait(millis, nanos);
                return readNow(cursor);
            }
        }

        private ReadResult readNow(long cursor) {
            if (closed.get() || state.unavailable) return new ReadResult(ReadStatus.UNAVAILABLE, null);
            Bounds bounds = bounds(state);
            if (cursor < bounds.earliest() - 1) return new ReadResult(ReadStatus.TOO_OLD, null);
            for (PublishedFrame frame : state.frames) {
                if (Long.parseLong(frame.event().eventId()) > cursor) {
                    return new ReadResult(ReadStatus.FRAME, frame);
                }
            }
            return new ReadResult(state.terminal ? ReadStatus.DONE : ReadStatus.IDLE, null);
        }

        @Override
        public void wake() {
            synchronized (state) { state.notifyAll(); }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (cacheLock) {
                synchronized (state) {
                    if (state.subscriptions > 0) state.subscriptions--;
                    if (activeSubscriptions > 0) activeSubscriptions--;
                    state.notifyAll();
                }
            }
        }
    }

    private void evictTerminalOverflow() {
        while (terminalOrder.size() > terminalCapacity) {
            Iterator<Map.Entry<String, StreamState>> iterator = terminalOrder.entrySet().iterator();
            Map.Entry<String, StreamState> eldest = iterator.next();
            iterator.remove();
            evict(eldest.getKey(), eldest.getValue());
        }
    }

    private void evict(String taskId, StreamState state) {
        streams.remove(taskId, state);
        synchronized (state) {
            state.makeUnavailable();
        }
    }

    private static void requireTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }

    private static final class StreamState {
        private final Deque<PublishedFrame> frames = new ArrayDeque<>();
        private long lastEventId;
        private int retainedBytes;
        private boolean terminal;
        private boolean unavailable;
        private long terminalTick;
        private int subscriptions;

        private StreamState(long lastEventId) {
            this.lastEventId = lastEventId;
        }

        private void makeUnavailable() {
            unavailable = true;
            frames.clear();
            retainedBytes = 0;
            notifyAll();
        }
    }
}
