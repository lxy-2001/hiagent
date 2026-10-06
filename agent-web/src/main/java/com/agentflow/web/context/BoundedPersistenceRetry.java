package com.agentflow.web.context;

import com.agentflow.core.context.ContextSessionException;
import com.agentflow.core.context.HistoryStore;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Transaction-free retries of the same persistence operation; never repeats model/tool execution. */
public final class BoundedPersistenceRetry {
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Duration[] BACKOFF = { Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofMillis(1000) };
    private final Duration window;
    private final java.util.List<Duration> backoff;
    private final Clock clock;
    private final Waiter waiter;
    @FunctionalInterface public interface Waiter { void await(Duration delay) throws InterruptedException; }
    public BoundedPersistenceRetry(Clock clock) { this(clock, delay -> Thread.sleep(delay.toMillis())); }
    public BoundedPersistenceRetry(Clock clock, Waiter waiter) {
        this(clock,waiter,4,java.util.List.of(BACKOFF),WINDOW);
    }

    public BoundedPersistenceRetry(Clock clock, Waiter waiter, int attempts, java.util.List<Duration> backoff, Duration window) {
        this.clock=Objects.requireNonNull(clock);this.waiter=Objects.requireNonNull(waiter);
        this.window=Objects.requireNonNull(window);this.backoff=java.util.List.copyOf(backoff);
        if(attempts<1||attempts>4||this.backoff.size()!=attempts-1||window.isNegative()||window.isZero()||window.compareTo(WINDOW)>0
            ||this.backoff.stream().anyMatch(d->d.isNegative()||d.isZero())) throw new IllegalArgumentException("Invalid bounded persistence retry configuration");
    }

    public <T> T execute(HistoryStore.Io io, String failureCode, Supplier<T> operation) {
        Objects.requireNonNull(io); Objects.requireNonNull(operation); Objects.requireNonNull(failureCode);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PERSISTENCE_RETRY_REQUIRES_NO_TRANSACTION");
        }
        Instant retryDeadline = clock.instant().plus(window);
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= backoff.size(); attempt++) {
            check(io, retryDeadline, failureCode, lastFailure);
            try { return operation.get(); }
            catch (RuntimeException failure) {
                if (!retryable(failure)) throw failure;
                lastFailure = failure;
                check(io, retryDeadline, failureCode, failure);
                if (attempt == backoff.size()) throw new ContextSessionException(failureCode, failure);
                Duration remaining = Duration.between(clock.instant(), earlier(io.deadline(), retryDeadline));
                if (remaining.compareTo(backoff.get(attempt)) <= 0) {
                    throw new ContextSessionException(io.deadline().isBefore(retryDeadline) ? "TIMED_OUT" : failureCode, failure);
                }
                try { waiter.await(backoff.get(attempt)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ContextSessionException("CANCELLED", interrupted);
                }
            }
        }
        throw new AssertionError("bounded retry fell through");
    }

    /** Delegate must be the transactional proxy: each attempt completes its own short transaction. */
    public HistoryStore history(HistoryStore delegate) {
        Objects.requireNonNull(delegate);
        return new HistoryStore() {
            @Override public State state(Scope scope, Io io) { return execute(io, "HISTORY_PERSISTENCE_FAILED", () -> delegate.state(scope, io)); }
            @Override public Page readAfter(Scope scope, long after, long until, int limit, long bytes, Io io) {
                return execute(io, "HISTORY_PERSISTENCE_FAILED", () -> delegate.readAfter(scope, after, until, limit, bytes, io));
            }
            @Override public Batch appendBatch(Scope scope, String id, String hash, java.util.List<Item> items, Io io) {
                // Store's stable mutation key checks the committed batch before allocating again.
                return execute(io, "HISTORY_PERSISTENCE_FAILED", () -> delegate.appendBatch(scope, id, hash, items, io));
            }
            @Override public Checkpoint saveCheckpoint(Scope scope, long revision, Checkpoint checkpoint, Io io) {
                return execute(io, "HISTORY_PERSISTENCE_FAILED", () -> delegate.saveCheckpoint(scope, revision, checkpoint, io));
            }
        };
    }

    private void check(HistoryStore.Io io, Instant retryDeadline, String code, RuntimeException cause) {
        if (io.cancellation().isCancelled() || Thread.currentThread().isInterrupted()) throw new ContextSessionException("CANCELLED", cause);
        Instant now = clock.instant();
        if (!now.isBefore(io.deadline())) throw new ContextSessionException("TIMED_OUT", cause);
        if (!now.isBefore(retryDeadline)) throw new ContextSessionException(code, cause);
    }
    private static Instant earlier(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static boolean retryable(RuntimeException failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                    || cause instanceof SQLTransientException || cause instanceof SQLRecoverableException
                    || cause instanceof LockTimeoutException || cause instanceof PessimisticLockException || cause instanceof QueryTimeoutException) return true;
            if (cause instanceof UncheckedIOException io && !(io.getCause() instanceof AccessDeniedException)
                    && !(io.getCause() instanceof NoSuchFileException)) return true;
            if (cause == cause.getCause()) break;
        }
        return false;
    }
}
