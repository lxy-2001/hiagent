package com.agentflow.web.artifact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps admission and global body deletion disjoint without holding a monitor during I/O. */
public final class ArtifactAdmissionCoordinator {
    private final Map<String, Integer> admissions = new HashMap<>();
    private final Set<String> evictions = new HashSet<>();

    public synchronized Optional<Lease> tryAdmission(String sessionId) {
        if (evictions.contains(sessionId)) return Optional.empty();
        admissions.merge(sessionId, 1, Math::addExact);
        return Optional.of(new Lease(() -> releaseAdmission(sessionId)));
    }

    public synchronized Optional<Lease> tryEviction(String sessionId) {
        if (admissions.containsKey(sessionId) || !evictions.add(sessionId)) return Optional.empty();
        return Optional.of(new Lease(() -> releaseEviction(sessionId)));
    }

    private synchronized void releaseAdmission(String sessionId) {
        admissions.computeIfPresent(sessionId, (id, count) -> count == 1 ? null : count - 1);
    }

    private synchronized void releaseEviction(String sessionId) {
        evictions.remove(sessionId);
    }

    public static final class Lease implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private final Runnable release;
        private Lease(Runnable release) { this.release = release; }
        @Override public void close() { if (released.compareAndSet(false, true)) release.run(); }
    }
}
