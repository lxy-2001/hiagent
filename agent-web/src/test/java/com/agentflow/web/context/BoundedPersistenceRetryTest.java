package com.agentflow.web.context;

import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.context.ContextSessionException;
import com.agentflow.core.context.HistoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class BoundedPersistenceRetryTest {
    @Test void temporaryFailureRecoversWithSameOperationAndNoTransactionDuringBackoff() {
        var clock = new MutableClock(); var waits = new ArrayList<Duration>(); var attempts = new AtomicInteger();
        var retry = retry(clock, waits);
        String result = retry.execute(io(clock, CancellationSignal.NONE), "HISTORY_PERSISTENCE_FAILED", () -> {
            if (attempts.incrementAndGet() < 3) throw temporary();
            return "confirmed original mutation";
        });
        assertThat(result).isEqualTo("confirmed original mutation");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(waits).containsExactly(Duration.ofMillis(200), Duration.ofMillis(500));
    }

    @Test void persistentFailureExhaustsExactlyFourAttemptsAndRetainsCause() {
        var clock = new MutableClock(); var waits = new ArrayList<Duration>(); var attempts = new AtomicInteger();
        var failure = temporary();
        assertThatThrownBy(() -> retry(clock, waits).execute(io(clock, CancellationSignal.NONE), "HISTORY_PERSISTENCE_FAILED", () -> {
            attempts.incrementAndGet(); throw failure;
        })).isInstanceOf(ContextSessionException.class).hasMessage("HISTORY_PERSISTENCE_FAILED").hasCause(failure);
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(waits).containsExactly(Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    @Test void nonRetryableFailurePropagatesImmediatelyAndActiveTransactionIsRejected() {
        var clock = new MutableClock(); var waits = new ArrayList<Duration>(); var attempts = new AtomicInteger();
        var failure = new IllegalStateException("HISTORY_MUTATION_CONFLICT");
        assertThatThrownBy(() -> retry(clock, waits).execute(io(clock, CancellationSignal.NONE), "HISTORY_PERSISTENCE_FAILED", () -> {
            attempts.incrementAndGet(); throw failure;
        })).isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(1); assertThat(waits).isEmpty();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> retry(clock, waits).execute(io(clock, CancellationSignal.NONE), "HISTORY_PERSISTENCE_FAILED", () -> "unexpected"))
                    .hasMessage("PERSISTENCE_RETRY_REQUIRES_NO_TRANSACTION");
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void cancellationDeadlineAndTenSecondWindowPreventFurtherIo() {
        var clock = new MutableClock(); var cancelled = new AtomicBoolean(); var attempts = new AtomicInteger();
        var retry = new BoundedPersistenceRetry(clock, delay -> { cancelled.set(true); clock.advance(delay); });
        assertThatThrownBy(() -> retry.execute(io(clock, cancelled::get), "HISTORY_PERSISTENCE_FAILED", () -> {
            attempts.incrementAndGet(); throw temporary();
        })).hasMessage("CANCELLED");
        assertThat(attempts.get()).isEqualTo(1);
        var expired = new HistoryStore.Io(CancellationSignal.NONE, clock.instant());
        assertThatThrownBy(() -> retry.execute(expired, "HISTORY_PERSISTENCE_FAILED", () -> { attempts.incrementAndGet(); return "wrong"; }))
                .hasMessage("TIMED_OUT");
        var waits = new ArrayList<Duration>(); attempts.set(0);
        assertThatThrownBy(() -> retry(clock, waits).execute(io(clock, CancellationSignal.NONE), "HISTORY_PERSISTENCE_FAILED", () -> {
            attempts.incrementAndGet(); clock.advance(Duration.ofSeconds(10)); throw temporary();
        })).hasMessage("HISTORY_PERSISTENCE_FAILED");
        assertThat(attempts.get()).isEqualTo(1); assertThat(waits).isEmpty();
    }

    @Test void configuredRetryBudgetUsesOnlyItsDeclaredAttempts() {
        var clock=new MutableClock();var attempts=new AtomicInteger();var waits=new ArrayList<Duration>();
        var retry=new BoundedPersistenceRetry(clock,delay->{waits.add(delay);clock.advance(delay);},2,List.of(Duration.ofMillis(20)),Duration.ofSeconds(1));
        assertThatThrownBy(()->retry.execute(io(clock,CancellationSignal.NONE),"HISTORY_PERSISTENCE_FAILED",()->{attempts.incrementAndGet();throw temporary();})).hasMessage("HISTORY_PERSISTENCE_FAILED");
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(waits).containsExactly(Duration.ofMillis(20));
    }

    private static BoundedPersistenceRetry retry(MutableClock clock, List<Duration> waits) {
        return new BoundedPersistenceRetry(clock, delay -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            waits.add(delay); clock.advance(delay);
        });
    }
    private static HistoryStore.Io io(Clock clock, CancellationSignal cancellation) {
        return new HistoryStore.Io(cancellation, clock.instant().plusSeconds(60));
    }
    private static RuntimeException temporary() { return new TransientDataAccessResourceException("database temporarily unavailable"); }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
