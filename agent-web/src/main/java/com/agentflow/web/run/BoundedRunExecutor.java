package com.agentflow.web.run;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BoundedRunExecutor implements AutoCloseable {

    public record Dispatch(boolean accepted, TaskHandle handle) {
    }

    public static final class TaskHandle {
        private final BoundedRunExecutor owner;
        private final TrackedTask task;

        private TaskHandle(BoundedRunExecutor owner, TrackedTask task) {
            this.owner = owner;
            this.task = task;
        }
    }

    private enum TaskState {
        QUEUED,
        RUNNING,
        REMOVED,
        REJECTED,
        EXITED
    }

    private final ThreadPoolExecutor executor;

    public BoundedRunExecutor(int workerCount, int queueCapacity, ThreadFactory threadFactory) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        Objects.requireNonNull(threadFactory, "threadFactory must not be null");
        executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    public Dispatch dispatch(Runnable task, Runnable onRejected, Runnable onExitedOrRemoved) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(onRejected, "onRejected must not be null");
        Objects.requireNonNull(onExitedOrRemoved, "onExitedOrRemoved must not be null");
        TrackedTask tracked = new TrackedTask(task, onExitedOrRemoved);
        TaskHandle handle = new TaskHandle(this, tracked);
        try {
            executor.execute(tracked);
            return new Dispatch(true, handle);
        } catch (RejectedExecutionException rejected) {
            tracked.markRejected();
            onRejected.run();
            return new Dispatch(false, null);
        }
    }

    public boolean remove(TaskHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        if (handle.owner != this) {
            throw new IllegalArgumentException("task handle belongs to another executor");
        }
        if (!executor.remove(handle.task)) {
            return false;
        }
        return handle.task.markRemoved();
    }

    public int poolSize() {
        return executor.getPoolSize();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    public void shutdown() {
        executor.shutdown();
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        shutdown();
    }

    private static final class TrackedTask implements Runnable {
        private final Runnable delegate;
        private final Runnable onExitedOrRemoved;
        private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);

        private TrackedTask(Runnable delegate, Runnable onExitedOrRemoved) {
            this.delegate = delegate;
            this.onExitedOrRemoved = onExitedOrRemoved;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(TaskState.QUEUED, TaskState.RUNNING)) {
                return;
            }
            try {
                delegate.run();
            } finally {
                state.set(TaskState.EXITED);
                onExitedOrRemoved.run();
            }
        }

        private boolean markRemoved() {
            if (!state.compareAndSet(TaskState.QUEUED, TaskState.REMOVED)) {
                return false;
            }
            onExitedOrRemoved.run();
            return true;
        }

        private void markRejected() {
            state.compareAndSet(TaskState.QUEUED, TaskState.REJECTED);
        }
    }
}
