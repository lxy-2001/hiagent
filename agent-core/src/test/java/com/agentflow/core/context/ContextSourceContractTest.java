package com.agentflow.core.context;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ContextSourceContractTest {
    @Test void sourceFailureAndCancellationRemainExplicit() {
        var query = new ContextSource.Query("u", "s", "r");
        var failure = new ContextSource.ContextSourceException("controlled source failure");
        ContextSource unavailable = (q, timeout, cancellation) -> { throw failure; };
        assertSame(failure, assertThrows(ContextSource.ContextSourceException.class,
                () -> unavailable.load(query, Duration.ofSeconds(1), () -> false)));
        ContextSource cancelled = (q, timeout, cancellation) -> {
            if (cancellation.isCancelled()) {
                throw new ContextSource.ContextSourceException("cancelled");
            }
            return ContextSeed.empty();
        };
        assertThrows(ContextSource.ContextSourceException.class,
                () -> cancelled.load(query, Duration.ofMillis(1), () -> true));
        assertThrows(IllegalArgumentException.class, () -> new ContextSource.Query("", "s", "r"));
        assertThrows(IllegalArgumentException.class, () -> ContextSource.bounded(Duration.ofMillis(-1)));
    }
    @Test void boundsTimeoutAndKeepsQueryPureJava() {
        assertEquals(Duration.ofSeconds(2), ContextSource.bounded(Duration.ofSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> ContextSource.bounded(Duration.ZERO));
        var query = new ContextSource.Query("u", "s", "r");
        ContextSource source = (q, timeout, cancellation) -> ContextSeed.empty();
        assertEquals(ContextSeed.empty(), source.load(query, Duration.ofSeconds(1), () -> false));
    }
}
