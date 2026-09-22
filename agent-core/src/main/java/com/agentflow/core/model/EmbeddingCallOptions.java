package com.agentflow.core.model;

import com.agentflow.core.cancel.CancellationSignal;

import java.time.Duration;
import java.util.Objects;

public record EmbeddingCallOptions(Duration remainingTime, CancellationSignal cancellationSignal) {
    public EmbeddingCallOptions {
        Objects.requireNonNull(remainingTime, "remainingTime");
        Objects.requireNonNull(cancellationSignal, "cancellationSignal");
        if (remainingTime.isNegative()) {
            throw new IllegalArgumentException("remainingTime must not be negative");
        }
        remainingTime.toNanos();
    }
}
