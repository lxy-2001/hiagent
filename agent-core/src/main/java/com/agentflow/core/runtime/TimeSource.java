package com.agentflow.core.runtime;

/** Monotonic time source injected for deterministic timeout tests. */
@FunctionalInterface
public interface TimeSource {
    long nanoTime();

    static TimeSource system() {
        return System::nanoTime;
    }
}
