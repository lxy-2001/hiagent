package com.agentflow.core.cancel;

@FunctionalInterface
public interface CancellationSignal {
    CancellationSignal NONE = () -> false;

    boolean isCancelled();
}
