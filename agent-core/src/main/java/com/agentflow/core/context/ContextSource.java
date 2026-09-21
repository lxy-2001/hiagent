package com.agentflow.core.context;

import com.agentflow.core.cancel.CancellationSignal;

import java.time.Duration;
import java.util.Objects;

/** One bounded snapshot read. Implementations must not conceal failures as empty history. */
@FunctionalInterface
public interface ContextSource {
    ContextSeed load(Query query, Duration timeout, CancellationSignal cancellation);

    record Query(String userId, String sessionId, String currentRunId) {
        public Query {
            requireIdentity(userId, "userId");
            requireIdentity(sessionId, "sessionId");
            requireIdentity(currentRunId, "currentRunId");
        }

        private static void requireIdentity(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    final class ContextSourceException extends RuntimeException {
        public ContextSourceException(String message, Throwable cause) {
            super(message, cause);
        }

        public ContextSourceException(String message) {
            super(message);
        }
    }

    /** Logical cooperative deadline, not a guarantee that JDBC can be forcibly interrupted. */
    static Duration bounded(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Duration maximum = Duration.ofSeconds(2);
        return timeout.compareTo(maximum) > 0 ? maximum : timeout;
    }
}

