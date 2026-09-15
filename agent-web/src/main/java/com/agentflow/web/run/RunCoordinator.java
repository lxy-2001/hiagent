package com.agentflow.web.run;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.runtime.AgentRunOptions;
import com.agentflow.core.runtime.ExecutionBudget;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Coordinates the single-process ownership of an accepted run. */
public final class RunCoordinator implements AutoCloseable {

    public enum Availability { READY, RECOVERING, DEGRADED, STOPPING }

    public record RunAccepted(String taskId, String runId, String sessionId,
                              RunLifecycleStatus status) { }

    private record OwnedRun(RunControl control, RunSnapshot accepted,
                            AtomicBoolean observationsComplete) { }

    private final AgentRuntime runtime;
    private final RunPersistence persistence;
    private final RunEventHub events;
    private final RunEventProjector eventProjector;
    private final RunResultProjector resultProjector;
    private final BoundedRunExecutor executor;
    private final RunLifecycleProperties properties;
    private final Clock clock;
    private final LongSupplier monotonicNanos;
    private final Supplier<String> ids;
    private final Map<String, OwnedRun> runs = new ConcurrentHashMap<>();
    private final Map<String, BoundedRunExecutor.TaskHandle> handles = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private volatile Availability availability = Availability.READY;
    private final ScheduledExecutorService maintenance;
    private int reserved;

    public RunCoordinator(AgentRuntime runtime, RunPersistence persistence, RunEventHub events,
                          RunEventProjector eventProjector, RunResultProjector resultProjector,
                          BoundedRunExecutor executor, RunLifecycleProperties properties,
                          Clock clock, LongSupplier monotonicNanos, Supplier<String> ids) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.eventProjector = Objects.requireNonNull(eventProjector, "eventProjector must not be null");
        this.resultProjector = Objects.requireNonNull(resultProjector, "resultProjector must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-run-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        this.maintenance.scheduleWithFixedDelay(this::maintainSafely, 1, 1, TimeUnit.SECONDS);
    }

    private void maintainSafely() {
        try { maintainOnce(); } catch (RuntimeException ignored) { availability = Availability.DEGRADED; }
    }

    public RunAccepted create(String userId, String input) {
        requireNonBlank(userId, "userId");
        String normalized = normalizeInput(input);
        reserve();
        String taskId = ids.get();
        String sessionId = ids.get();
        Instant createdAt = clock.instant();
        long acceptedTick = monotonicNanos.getAsLong();
        long deadline = Math.addExact(acceptedTick, properties.queueTimeout().toNanos());
        RunControl control = RunControl.queued(taskId, userId, acceptedTick, deadline);
        try {
            RunSnapshot snapshot = persistence.createQueued(new RunPersistence.CreateCommand(taskId,
                    sessionId, userId, normalized, title(normalized), createdAt));
            OwnedRun owned = new OwnedRun(control, snapshot, new AtomicBoolean(true));
            runs.put(taskId, owned);
            events.create(taskId);
            publish(taskId, RunEvent.Type.RUN_CREATED, createdAt,
                    Map.of("sessionId", sessionId, "status", RunLifecycleStatus.QUEUED.name()));
            BoundedRunExecutor.Dispatch dispatch = executor.dispatch(() -> execute(owned),
                    () -> rejectDispatch(owned), () -> workerLeft(owned));
            if (dispatch.accepted()) {
                handles.put(taskId, dispatch.handle());
            }
            return new RunAccepted(taskId, taskId, sessionId, RunLifecycleStatus.QUEUED);
        } catch (RuntimeException failure) {
            runs.remove(taskId);
            releaseReservation();
            throw failure;
        }
    }

    private void execute(OwnedRun owned) {
        RunControl control = owned.control();
        control.markWorkerEntered();
        if (availability != Availability.READY && availability != Availability.STOPPING) {
            failWithoutRuntime(owned, RunResultProjector.FailureKind.PERSISTENCE_UNAVAILABLE);
            return;
        }
        RunControl.StartClaim claim = control.claimStart(monotonicNanos.getAsLong());
        if (claim == RunControl.StartClaim.EXPIRED) {
            failWithoutRuntime(owned, RunResultProjector.FailureKind.QUEUE_TIMEOUT);
            return;
        }
        if (claim != RunControl.StartClaim.START) {
            return;
        }
        Instant startedAt = clock.instant();
        RunPersistence.StartResult start;
        try {
            start = persistence.markRunning(control.taskId(), startedAt);
        } catch (RuntimeException failure) {
            availability = Availability.DEGRADED;
            control.freezeFinal(resultProjector.projectFailure(control.taskId(),
                    RunResultProjector.FailureKind.PERSISTENCE_UNAVAILABLE, clock.instant(), control.isCancelled()));
            return;
        }
        if (start.outcome() == RunPersistence.StartOutcome.TERMINAL) {
            control.markTerminalConfirmed();
            return;
        }
        if (start.outcome() != RunPersistence.StartOutcome.STARTED
                && start.outcome() != RunPersistence.StartOutcome.ALREADY_RUNNING) {
            failWithoutRuntime(owned, RunResultProjector.FailureKind.PERSISTENCE_UNAVAILABLE);
            return;
        }
        publish(control.taskId(), RunEvent.Type.RUN_STARTED, startedAt,
                Map.of("status", RunLifecycleStatus.RUNNING.name(), "startedAt", startedAt));
        RunControl.RuntimeCallClaim runtimeClaim = control.claimRuntimeCall(true);
        if (runtimeClaim == RunControl.RuntimeCallClaim.CANCEL_BEFORE_CALL) {
            finish(owned, cancelled(control.taskId(), clock.instant()));
            return;
        }
        if (runtimeClaim != RunControl.RuntimeCallClaim.CALL) {
            return;
        }
        AgentResult result;
        try {
            AgentRequest request = new AgentRequest(control.taskId(), owned.accepted().sessionId(),
                    control.userId(), owned.accepted().input());
            result = runtime.run(request, event -> observe(owned, event),
                    new AgentRunOptions(ExecutionBudget.defaults(), control));
        } catch (RuntimeException failure) {
            result = null;
        }
        RunResultProjector.FinalProjection projection = result == null
                ? resultProjector.projectFailure(control.taskId(),
                    RunResultProjector.FailureKind.INTERNAL_ERROR, clock.instant(), control.isCancelled())
                : resultProjector.project(control.taskId(), result, clock.instant(), control.isCancelled(),
                    owned.observationsComplete().get());
        finish(owned, projection);
    }

    private void observe(OwnedRun owned, AgentEvent event) {
        if (owned.control().phase() != RunControl.Phase.EXECUTING) {
            owned.observationsComplete().set(false);
            return;
        }
        RunEventProjector.Projection projection = eventProjector.project(owned.control().taskId(),
                event, clock.instant());
        if (!projection.observationComplete()) {
            owned.observationsComplete().set(false);
        }
        projection.event().ifPresent(draft -> {
            RunEventHub.PublishResult published = events.publish(owned.control().taskId(), draft);
            if (published.status() != RunEventHub.PublishStatus.PUBLISHED) {
                owned.observationsComplete().set(false);
            }
        });
    }

    private void rejectDispatch(OwnedRun owned) {
        owned.control().markQueueDetached();
        failWithoutRuntime(owned, RunResultProjector.FailureKind.DISPATCH_REJECTED);
    }

    private void failWithoutRuntime(OwnedRun owned, RunResultProjector.FailureKind reason) {
        finish(owned, resultProjector.projectFailure(owned.control().taskId(), reason,
                clock.instant(), owned.control().isCancelled()));
    }

    private void finish(OwnedRun owned, RunResultProjector.FinalProjection projection) {
        RunControl control = owned.control();
        control.freezeFinal(projection);
        RunControl.PendingClaim pending = control.claimPending().orElse(null);
        try {
            RunPersistence.CommittedTerminal committed = persistence.complete(projection);
            if (pending != null) control.finishPending(pending, RunControl.PendingOutcome.TERMINAL_CONFIRMED);
            else control.markTerminalConfirmed();
            RunSnapshot fact = committed.snapshot();
            publish(fact.taskId(), RunEvent.Type.RUN_TERMINATED, fact.finishedAt(), terminalPayload(fact));
            events.markTerminal(fact.taskId());
        } catch (RuntimeException failure) {
            if (pending != null) control.finishPending(pending, RunControl.PendingOutcome.RETRY);
            availability = Availability.DEGRADED;
        } finally {
            tryRelease(owned);
        }
    }

    private static RunResultProjector.FinalProjection cancelled(String taskId, Instant at) {
        return new RunResultProjector.FinalProjection(taskId, RunLifecycleStatus.CANCELLED,
                RunTerminationReason.CANCELLED, null, null, null, at, true, false,
                RunTerminationReason.CANCELLED.name(), java.util.List.of());
    }

    private void workerLeft(OwnedRun owned) {
        owned.control().markExecutorSlotReleased();
        handles.remove(owned.control().taskId());
        tryRelease(owned);
    }

    private void tryRelease(OwnedRun owned) {
        if (owned.control().claimRelease()) {
            runs.remove(owned.control().taskId(), owned);
            handles.remove(owned.control().taskId());
            releaseReservation();
        }
    }

    private void publish(String taskId, RunEvent.Type type, Instant at, Object payload) {
        events.publish(taskId, new RunEvent.Draft(taskId, taskId, type, at, payload));
    }

    public Optional<RunControl> control(String taskId) {
        OwnedRun owned = runs.get(taskId);
        return owned == null ? Optional.empty() : Optional.of(owned.control());
    }

    public RunSnapshot getOwned(String userId, String taskId) {
        requireNonBlank(userId, "userId");
        requireNonBlank(taskId, "taskId");
        OwnedRun local = runs.get(taskId);
        if (local != null && local.control().pending().isPresent()) {
            throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", taskId);
        }
        return persistence.getOwned(userId, taskId).orElseThrow(RunNotFoundException::new);
    }

    public java.util.List<RunResultProjector.ProjectedStep> getOwnedSteps(String userId, String taskId) {
        getOwned(userId, taskId);
        return persistence.getOwnedSteps(userId, taskId);
    }

    public record CancelReply(RunSnapshot snapshot, boolean accepted) { }

    public CancelReply cancel(String userId, String taskId) {
        RunSnapshot current = getOwned(userId, taskId);
        if (current.status().isTerminal()) return new CancelReply(current, false);
        OwnedRun owned = runs.get(taskId);
        if (owned == null) throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", taskId);
        RunControl.CancelClaim claim = owned.control().requestCancel();
        if (claim == RunControl.CancelClaim.CANCEL_BEFORE_START) {
            BoundedRunExecutor.TaskHandle handle = handles.get(taskId);
            if (handle != null) executor.remove(handle);
            finish(owned, cancelled(taskId, clock.instant()));
            return new CancelReply(persistence.getOwned(userId, taskId)
                    .orElseThrow(RunNotFoundException::new), false);
        }
        if (claim == RunControl.CancelClaim.TERMINAL) return new CancelReply(getOwned(userId, taskId), false);
        try {
            RunPersistence.CancelResult result = persistence.requestCancellation(taskId, clock.instant());
            if (result.outcome() == RunPersistence.CancelOutcome.MISSING) throw new RunNotFoundException();
            if (result.outcome() == RunPersistence.CancelOutcome.TERMINAL) return new CancelReply(result.snapshot(), false);
            return new CancelReply(result.snapshot(), true);
        } catch (RunNotFoundException exception) {
            throw exception;
        } catch (RuntimeException failure) {
            owned.control().markCancellationPending();
            availability = Availability.DEGRADED;
            throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", taskId);
        }
    }

    public int inFlightCount() {
        synchronized (admissionLock) { return reserved; }
    }

    public Availability availability() { return availability; }

    public void markReady() { availability = Availability.READY; }

    /** Performs one bounded, serial retry pass; it never invokes the Runtime. */
    public void maintainOnce() {
        expireQueuedRuns();
        boolean failed = false;
        for (OwnedRun owned : java.util.List.copyOf(runs.values())) {
            RunControl.PendingClaim claim = owned.control().claimPending().orElse(null);
            if (claim == null) continue;
            try {
                if (claim.kind() == RunControl.PendingKind.FINAL_PENDING
                        && claim.payload() instanceof RunResultProjector.FinalProjection projection) {
                    RunPersistence.CommittedTerminal committed = persistence.complete(projection);
                    owned.control().finishPending(claim, RunControl.PendingOutcome.TERMINAL_CONFIRMED);
                    RunSnapshot fact = committed.snapshot();
                    publish(fact.taskId(), RunEvent.Type.RUN_TERMINATED, fact.finishedAt(), terminalPayload(fact));
                    events.markTerminal(fact.taskId());
                    tryRelease(owned);
                } else if (claim.kind() == RunControl.PendingKind.CANCEL_FLAG_PENDING) {
                    RunPersistence.CancelResult result = persistence.requestCancellation(
                            owned.control().taskId(), clock.instant());
                    owned.control().finishPending(claim, result.outcome() == RunPersistence.CancelOutcome.TERMINAL
                            ? RunControl.PendingOutcome.TERMINAL_CONFIRMED : RunControl.PendingOutcome.CONFIRMED);
                } else {
                    owned.control().finishPending(claim, RunControl.PendingOutcome.RETRY);
                }
            } catch (RuntimeException retryFailure) {
                owned.control().finishPending(claim, RunControl.PendingOutcome.RETRY);
                failed = true;
            }
        }
        if (!failed && runs.values().stream().noneMatch(r -> r.control().pending().isPresent())) {
            availability = Availability.READY;
        } else if (failed) availability = Availability.DEGRADED;
    }

    private void expireQueuedRuns() {
        long nowTick = monotonicNanos.getAsLong();
        for (OwnedRun owned : java.util.List.copyOf(runs.values())) {
            RunControl control = owned.control();
            if (nowTick - control.queueDeadline() < 0
                    || control.claimStart(nowTick) != RunControl.StartClaim.EXPIRED) {
                continue;
            }
            BoundedRunExecutor.TaskHandle handle = handles.get(control.taskId());
            if (handle != null) {
                executor.remove(handle);
            }
            failWithoutRuntime(owned, RunResultProjector.FailureKind.QUEUE_TIMEOUT);
        }
    }

    public boolean recoverInterrupted() {
        availability = Availability.RECOVERING;
        String after = "";
        try {
            while (true) {
                java.util.List<RunSnapshot> batch = persistence.convergeInterrupted(after, 100, clock.instant());
                if (batch.isEmpty()) break;
                after = batch.get(batch.size() - 1).taskId();
                if (batch.size() < 100) break;
            }
            availability = Availability.READY;
            return true;
        } catch (RuntimeException failure) {
            availability = Availability.DEGRADED;
            return false;
        }
    }

    private static Map<String, Object> terminalPayload(RunSnapshot fact) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", fact.status().name());
        payload.put("terminationReason", fact.terminationReason().name());
        payload.put("runtimeReason", fact.runtimeReason() == null ? null : fact.runtimeReason().name());
        payload.put("finishedAt", fact.finishedAt());
        payload.put("recordingComplete", fact.recordingComplete());
        return payload;
    }

    private void reserve() {
        synchronized (admissionLock) {
            if (availability == Availability.STOPPING) {
                throw new RunUnavailableException("SERVICE_STOPPING", null);
            }
            if (availability != Availability.READY) {
                throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", null);
            }
            if (reserved >= properties.inFlightCapacity()) {
                throw new RunCapacityException();
            }
            reserved++;
        }
    }

    private void releaseReservation() {
        synchronized (admissionLock) {
            if (reserved > 0) reserved--;
        }
    }

    private static String normalizeInput(String input) {
        requireNonBlank(input, "input");
        String value = input.strip();
        if (value.length() > 8_000) throw new IllegalArgumentException("input must contain at most 8000 characters");
        return value;
    }

    private static String title(String input) { return input.length() <= 48 ? input : input.substring(0, 48); }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    @Override
    public void close() {
        availability = Availability.STOPPING;
        maintenance.shutdownNow();
        runs.values().forEach(run -> run.control().requestCancel());
        executor.shutdown();
        try {
            executor.awaitTermination(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class RunCapacityException extends RuntimeException { }
    public static final class RunNotFoundException extends RuntimeException { }

    public static final class RunUnavailableException extends RuntimeException {
        private final String code;
        private final String taskId;
        public RunUnavailableException(String code, String taskId) { this.code = code; this.taskId = taskId; }
        public String code() { return code; }
        public String taskId() { return taskId; }
    }
}
