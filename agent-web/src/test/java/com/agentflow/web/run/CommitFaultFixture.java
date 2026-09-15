package com.agentflow.web.run;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class CommitFaultFixture {

    enum Mode {
        NONE,
        ROLLBACK_BEFORE_COMMIT,
        LOSE_CONFIRMATION_AFTER_COMMIT,
        UNRESOLVED_IO_AFTER_COMMIT,
        DELAY_COMMITTED_ROW_VISIBILITY
    }

    private static final Duration INTERNAL_WAIT_LIMIT = Duration.ofSeconds(5);

    private final Mode mode;
    private final CountDownLatch commitReturned = new CountDownLatch(1);
    private final CountDownLatch ioPending = new CountDownLatch(1);
    private final CountDownLatch ioRelease = new CountDownLatch(1);
    private final CountDownLatch visibilityRelease = new CountDownLatch(1);

    CommitFaultFixture(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    <T> T execute(TransactionTemplate transaction, Supplier<T> work) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(work, "work must not be null");
        T result = transaction.execute(status -> {
            if (mode == Mode.ROLLBACK_BEFORE_COMMIT) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new BeforeCommitFailure("controlled failure before transaction commit");
                    }
                });
            }
            return work.get();
        });
        commitReturned.countDown();

        if (mode == Mode.LOSE_CONFIRMATION_AFTER_COMMIT) {
            throw new CommitOutcomeUnknownException("transaction committed but confirmation was lost");
        }
        if (mode == Mode.UNRESOLVED_IO_AFTER_COMMIT) {
            ioPending.countDown();
            awaitRelease(ioRelease, "unresolved I/O");
            throw new CommitOutcomeUnknownException("transaction committed while I/O outcome remained unresolved");
        }
        return result;
    }

    <T> T observeCommittedRow(Supplier<T> query, T hiddenValue) {
        Objects.requireNonNull(query, "query must not be null");
        if (mode == Mode.DELAY_COMMITTED_ROW_VISIBILITY && visibilityRelease.getCount() > 0) {
            return hiddenValue;
        }
        return query.get();
    }

    boolean awaitCommitReturned(Duration timeout) throws InterruptedException {
        return await(commitReturned, timeout);
    }

    boolean awaitIoPending(Duration timeout) throws InterruptedException {
        return await(ioPending, timeout);
    }

    void releaseIo() {
        ioRelease.countDown();
    }

    void revealCommittedRow() {
        visibilityRelease.countDown();
    }

    private static boolean await(CountDownLatch latch, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void awaitRelease(CountDownLatch latch, String boundary) {
        try {
            if (!latch.await(INTERNAL_WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(boundary + " fixture was not released within " + INTERNAL_WAIT_LIMIT);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(boundary + " fixture wait was interrupted", interrupted);
        }
    }

    static final class BeforeCommitFailure extends RuntimeException {
        BeforeCommitFailure(String message) {
            super(message);
        }
    }

    static final class CommitOutcomeUnknownException extends RuntimeException {
        CommitOutcomeUnknownException(String message) {
            super(message);
        }
    }
}
