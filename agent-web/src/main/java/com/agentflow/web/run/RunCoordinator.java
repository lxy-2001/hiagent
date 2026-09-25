package com.agentflow.web.run;

import com.agentflow.core.context.ContextSource;
import com.agentflow.core.context.ContextSeed;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.web.conversation.ConversationProperties;
import java.time.Duration;

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

    private record OwnedRun(RunControl control, RunPersistence.CreateCommand command,
                            AtomicBoolean observationsComplete) { }

    private final AgentRuntime runtime;
    private final ContextSource contextSource;
    private final Duration sourceTimeout;
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
    private com.agentflow.web.approval.ApprovalService approvals;
    private com.agentflow.core.approval.ApprovalGate applicationApprovalGate;
    public void configureApprovalGate(com.agentflow.core.approval.ApprovalGate gate) {
        this.applicationApprovalGate = Objects.requireNonNull(gate);
    }

    private java.time.Duration approvalTtl = java.time.Duration.ofSeconds(30);

    public void configureApprovals(com.agentflow.web.approval.ApprovalService service, java.time.Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(java.time.Duration.ofSeconds(120)) > 0)
            throw new IllegalArgumentException("invalid approval TTL");
        this.approvals = Objects.requireNonNull(service); this.approvalTtl = ttl;
    }

    private final Object admissionLock = new Object();
    private volatile Availability availability = Availability.READY;
    private volatile boolean startupRecoveryBlocked;
    private final ScheduledExecutorService maintenance;
    private int reserved;
    private final Map<String, String> sessionReservations = new java.util.HashMap<>();
    private final ContextTextPolicy textPolicy = new ContextTextPolicy();

    public RunCoordinator(ContextSource contextSource, AgentRuntime runtime, RunPersistence persistence, RunEventHub events,
                          RunEventProjector eventProjector, RunResultProjector resultProjector,
                          BoundedRunExecutor executor, RunLifecycleProperties properties,
                          Clock clock, LongSupplier monotonicNanos, Supplier<String> ids) {
        this(contextSource, runtime, persistence, events, eventProjector, resultProjector, executor, properties,
                clock, monotonicNanos, ids, ConversationProperties.defaults());
    }

    public RunCoordinator(ContextSource contextSource, AgentRuntime runtime, RunPersistence persistence, RunEventHub events,
                          RunEventProjector eventProjector, RunResultProjector resultProjector,
                          BoundedRunExecutor executor, RunLifecycleProperties properties,
                          Clock clock, LongSupplier monotonicNanos, Supplier<String> ids, ConversationProperties conversationProperties) {
        this.sourceTimeout = Objects.requireNonNull(conversationProperties).sourceTimeout();
        this.contextSource = Objects.requireNonNull(contextSource, "contextSource must not be null");
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
        return create(userId, input, null);
    }

    public RunAccepted create(String userId, String input, String requestedSessionId) {
        return create(userId, input, requestedSessionId, false);
    }

    public RunAccepted create(String userId, String input, String requestedSessionId, boolean requireEvidence) {
        requireNonBlank(userId, "userId");
        String normalized = normalizeInput(input);
        boolean createSession = requestedSessionId == null;
        if (!createSession) {
            requireNonBlank(requestedSessionId, "sessionId");
            if (requestedSessionId.length() > 36) throw new IllegalArgumentException("invalid sessionId");
            if (!persistence.ownsSession(userId, requestedSessionId)) throw new RunNotFoundException();
        }
        String taskId = ids.get();
        String sessionId = createSession ? ids.get() : requestedSessionId;
        Instant createdAt = clock.instant();
        long acceptedTick = monotonicNanos.getAsLong();
        long deadline = Math.addExact(acceptedTick, properties.queueTimeout().toNanos());
        RunControl control = RunControl.queued(taskId, userId, acceptedTick, deadline);
        RunPersistence.CreateCommand command = new RunPersistence.CreateCommand(taskId,
                sessionId, userId, normalized, title(normalized), createdAt, createSession, requireEvidence);
        OwnedRun owned = new OwnedRun(control, command, new AtomicBoolean(true));
        RunSnapshot snapshot;
        reserve(sessionId, taskId);
        try {
            snapshot = persistence.createQueued(command);
        } catch (RunPersistence.CreateRejectedException rejected) {
            releaseReservation(sessionId, taskId);
            if ("NOT_FOUND".equals(rejected.code())) throw new RunNotFoundException();
            throw new RunUnavailableException(rejected.code(), null);
        } catch (RuntimeException failure) {
            synchronized (admissionLock) {
                control.markCreateUncertain(command);
                runs.put(taskId, owned);
                availability = Availability.DEGRADED;
            }
            throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", taskId);
        }
        runs.put(taskId, owned);
        activateCommitted(owned, snapshot);
        return new RunAccepted(taskId, taskId, sessionId, RunLifecycleStatus.QUEUED);
    }

    private void activateCommitted(OwnedRun owned, RunSnapshot snapshot) {
        String taskId = owned.control().taskId();
        announceCommitted(snapshot);
        BoundedRunExecutor.Dispatch dispatch = executor.dispatch(() -> execute(owned),
                () -> rejectDispatch(owned), () -> workerLeft(owned));
        if (dispatch.accepted()) {
            handles.put(taskId, dispatch.handle());
            // A fast worker can finish before dispatch returns its handle.
            // Recheck after publication so either this branch or tryRelease removes it.
            if (owned.control().phase() == RunControl.Phase.RELEASED) {
                handles.remove(taskId, dispatch.handle());
            }
        }
    }

    private void announceCommitted(RunSnapshot snapshot) {
        String taskId = snapshot.taskId();
        events.create(taskId);
        publish(taskId, RunEvent.Type.RUN_CREATED, snapshot.createdAt(),
                Map.of("sessionId", snapshot.sessionId(), "status", RunLifecycleStatus.QUEUED.name()));
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
            synchronized (admissionLock) {
                control.markStartUncertain(startedAt);
                availability = Availability.DEGRADED;
            }
            RunControl.StartResolution resolution = control.awaitStartResolution();
            if (resolution == RunControl.StartResolution.STOPPED) return;
            if (resolution == RunControl.StartResolution.TERMINAL) return;
            start = new RunPersistence.StartResult(
                    RunPersistence.StartOutcome.ALREADY_RUNNING, null);
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
        long preparationStarted = monotonicNanos.getAsLong();
        ExecutionBudget defaults = ExecutionBudget.defaults();
        ExecutionBudget totalBudget = new ExecutionBudget(defaults.maxIterations(), properties.maxDuration(),
                defaults.maxPromptTokens(), defaults.maxCompletionTokens());
        ContextSeed seed = null;
        boolean sourceFailed = false;
        if (control.isCancelled()) {
            finish(owned, cancelled(control.taskId(), clock.instant()));
            return;
        }
        try {
            seed = Objects.requireNonNull(contextSource.load(new ContextSource.Query(
                    control.userId(), owned.command().sessionId(), control.taskId()), sourceTimeout, control));
        } catch (RuntimeException failure) {
            sourceFailed = true;
        }
        long preparationNanos;
        try { preparationNanos = Math.max(0, Math.subtractExact(monotonicNanos.getAsLong(), preparationStarted)); }
        catch (ArithmeticException overflow) { preparationNanos = Long.MAX_VALUE; }
        if (control.isCancelled()) {
            finish(owned, cancelled(control.taskId(), clock.instant()));
            return;
        }
        if (preparationNanos >= totalBudget.maxDuration().toNanos()) {
            finish(owned, new RunResultProjector.FinalProjection(control.taskId(), RunLifecycleStatus.TIMED_OUT,
                    RunTerminationReason.TIMED_OUT, null, null, null, clock.instant(), false, false,
                    "TIMED_OUT", java.util.List.of()));
            return;
        }
        if (sourceFailed || preparationNanos >= sourceTimeout.toNanos()) {
            failWithoutRuntime(owned, RunResultProjector.FailureKind.CONTEXT_SOURCE_UNAVAILABLE);
            return;
        }
        AgentResult result;
        var gate = applicationApprovalGate != null || approvals == null ? null : new com.agentflow.web.approval.WebApprovalGate(approvals, control, draft -> {
            try {
                if (events.publish(control.taskId(), draft).status() != RunEventHub.PublishStatus.PUBLISHED)
                    owned.observationsComplete().set(false);
            } catch (RuntimeException failure) { owned.observationsComplete().set(false); }
        }, () -> monotonicNanos.getAsLong() - preparationStarted >= totalBudget.maxDuration().toNanos());
        try {
            AgentRequest request = new AgentRequest(control.taskId(), owned.command().sessionId(),
                    control.userId(), owned.command().input(), seed, owned.command().requireEvidence());
            ExecutionBudget remaining = new ExecutionBudget(totalBudget.maxIterations(),
                    totalBudget.maxDuration().minusNanos(preparationNanos), totalBudget.maxPromptTokens(), totalBudget.maxCompletionTokens());
            result = runtime.run(request, event -> observe(owned, event),
                    new AgentRunOptions(remaining, control, applicationApprovalGate == null ? gate : applicationApprovalGate, approvalTtl));
        } catch (RuntimeException failure) {
            result = null;
        }
        if (gate != null) gate.close();
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
        if (pending == null) {
            availability = Availability.DEGRADED;
            tryRelease(owned);
            return;
        }
        try {
            RunPersistence.CommittedTerminal committed = persistence.complete(projection);
            control.finishPending(pending, RunControl.PendingOutcome.TERMINAL_CONFIRMED);
            publishResolved(committed);
            RunSnapshot fact = committed.snapshot();
            publish(fact.taskId(), RunEvent.Type.RUN_TERMINATED, fact.finishedAt(), terminalPayload(fact));
            events.markTerminal(fact.taskId());
        } catch (RuntimeException failure) {
            control.finishPending(pending, RunControl.PendingOutcome.RETRY);
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
            releaseReservation(owned.command().sessionId(), owned.control().taskId());
        }
    }

    private void publishResolved(RunPersistence.CommittedTerminal committed) {
        for (var approval : committed.resolvedApprovals()) {
            try {
                publish(approval.taskId(), RunEvent.Type.APPROVAL_RESOLVED, approval.decidedAt(),
                        new com.agentflow.web.approval.ApprovalService.Resolved(approval.approvalId(), approval.callId(),
                                approval.status().name(), approval.decisionSource().name(), approval.decidedAt(), approval.waitMillis()));
            } catch (RuntimeException ignored) { /* The committed query snapshot remains authoritative. */ }
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
        if (local != null && !userId.equals(local.control().userId())) {
            throw new RunNotFoundException();
        }
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
        requireNonBlank(userId, "userId");
        requireNonBlank(taskId, "taskId");
        OwnedRun local = runs.get(taskId);
        if (local != null && !userId.equals(local.control().userId())) {
            throw new RunNotFoundException();
        }
        if (local != null) {
            Optional<RunControl.PendingView> pending = local.control().pending();
            if (pending.isPresent()) {
                if (pending.orElseThrow().kind() == RunControl.PendingKind.START_UNCERTAIN) {
                    local.control().requestCancel();
                }
                throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", taskId);
            }
        }
        RunSnapshot current = persistence.getOwned(userId, taskId)
                .orElseThrow(RunNotFoundException::new);
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
        RunControl.PendingClaim pending = owned.control().claimCancellationWrite().orElse(null);
        if (pending == null || pending.kind() != RunControl.PendingKind.CANCEL_FLAG_PENDING) {
            availability = Availability.DEGRADED;
            throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", taskId);
        }
        try {
            RunPersistence.CancelResult result = persistence.requestCancellation(taskId, clock.instant());
            if (result.outcome() == RunPersistence.CancelOutcome.MISSING) throw new RunNotFoundException();
            boolean remains = owned.control().finishPending(pending,
                    result.outcome() == RunPersistence.CancelOutcome.TERMINAL
                            ? RunControl.PendingOutcome.TERMINAL_CONFIRMED
                            : RunControl.PendingOutcome.CONFIRMED);
            if (remains) availability = Availability.DEGRADED;
            if (result.outcome() == RunPersistence.CancelOutcome.TERMINAL) {
                tryRelease(owned);
                return new CancelReply(result.snapshot(), false);
            }
            return new CancelReply(result.snapshot(), true);
        } catch (RunNotFoundException exception) {
            owned.control().finishPending(pending, RunControl.PendingOutcome.RETRY);
            throw exception;
        } catch (RuntimeException failure) {
            owned.control().finishPending(pending, RunControl.PendingOutcome.RETRY);
            availability = Availability.DEGRADED;
            throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", taskId);
        }
    }

    public int inFlightCount() {
        synchronized (admissionLock) { return reserved; }
    }

    public Availability availability() { return availability; }
    public boolean isStartupRecoveryBlocked() { return startupRecoveryBlocked; }

    public void markReady() {
        synchronized (admissionLock) {
            if (!startupRecoveryBlocked && availability != Availability.STOPPING
                    && runs.values().stream().noneMatch(run -> (run.control().pending().isPresent() || run.control().isApprovalUncertain()))) {
                availability = Availability.READY;
            }
        }
    }

    /** Performs one bounded, serial retry pass; it never invokes the Runtime. */
    public void maintainOnce() {
        events.maintain();
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
                    publishResolved(committed);
                    RunSnapshot fact = committed.snapshot();
                    publish(fact.taskId(), RunEvent.Type.RUN_TERMINATED, fact.finishedAt(), terminalPayload(fact));
                    events.markTerminal(fact.taskId());
                    tryRelease(owned);
                } else if (claim.kind() == RunControl.PendingKind.CREATE_UNCERTAIN) {
                    Optional<RunSnapshot> committed = persistence.getOwned(
                            owned.control().userId(), owned.control().taskId());
                    if (committed.isEmpty()) {
                        owned.control().finishPending(claim, RunControl.PendingOutcome.RETRY);
                        failed = true;
                        continue;
                    }
                    owned.control().finishPending(claim, RunControl.PendingOutcome.CONFIRMED);
                    owned.control().markCreateConfirmed();
                    announceCommitted(committed.orElseThrow());
                    owned.control().markQueueDetached();
                    failWithoutRuntime(owned, RunResultProjector.FailureKind.DISPATCH_REJECTED);
                } else if (claim.kind() == RunControl.PendingKind.START_UNCERTAIN
                        && claim.payload() instanceof Instant startedAt) {
                    RunPersistence.StartResult result = persistence.markRunning(
                            owned.control().taskId(), startedAt);
                    if (result.outcome() == RunPersistence.StartOutcome.STARTED
                            || result.outcome() == RunPersistence.StartOutcome.ALREADY_RUNNING) {
                        owned.control().finishStartPending(
                                claim, RunControl.StartResolution.RUNNING);
                    } else if (result.outcome() == RunPersistence.StartOutcome.TERMINAL) {
                        owned.control().finishStartPending(
                                claim, RunControl.StartResolution.TERMINAL);
                    } else {
                        owned.control().finishPending(claim, RunControl.PendingOutcome.RETRY);
                        failed = true;
                    }
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
        if (runs.values().stream().anyMatch(r -> r.control().isApprovalUncertain())) failed = true;
        synchronized (admissionLock) {
            if (availability == Availability.STOPPING) {
                return;
            }
            if (!startupRecoveryBlocked && !failed
                    && runs.values().stream().noneMatch(r -> (r.control().pending().isPresent() || r.control().isApprovalUncertain()))) {
                availability = Availability.READY;
            } else if (failed) availability = Availability.DEGRADED;
        }
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
        startupRecoveryBlocked = true;
        availability = Availability.RECOVERING;
        String after = "";
        try {
            while (true) {
                java.util.List<RunSnapshot> batch = persistence.convergeInterrupted(after, 100, clock.instant());
                if (batch.isEmpty()) break;
                after = batch.get(batch.size() - 1).taskId();
                if (batch.size() < 100) break;
            }
            startupRecoveryBlocked = false;
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

    private void reserve(String sessionId, String taskId) {
        synchronized (admissionLock) {
            if (availability == Availability.STOPPING) {
                throw new RunUnavailableException("SERVICE_STOPPING", null);
            }
            if (runs.values().stream().anyMatch(r -> r.control().isApprovalUncertain())) availability = Availability.DEGRADED;
            if (availability != Availability.READY) {
                throw new RunUnavailableException("PERSISTENCE_UNAVAILABLE", null);
            }
            if (sessionReservations.containsKey(sessionId)) throw new SessionBusyException();
            if (reserved >= properties.inFlightCapacity()) {
                throw new RunCapacityException();
            }
            sessionReservations.put(sessionId, taskId);
            reserved++;
        }
    }

    private void releaseReservation(String sessionId, String taskId) {
        synchronized (admissionLock) {
            if (sessionReservations.remove(sessionId, taskId)) reserved--;
        }
    }

    private String normalizeInput(String input) {
        requireNonBlank(input, "input");
        if (input.length() > 8000) throw new IllegalArgumentException("input exceeds limit");
        String value = textPolicy.sanitizeInput(input.strip());
        if (value.length() > 8_000) throw new IllegalArgumentException("input must contain at most 8000 characters");
        return value;
    }

    private static String title(String input) {
        if (input.length() <= 48) return input;
        int end = Character.isHighSurrogate(input.charAt(47)) ? 47 : 48;
        return input.substring(0, end);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    @Override
    public void close() {
        availability = Availability.STOPPING;
        maintenance.shutdownNow();
        runs.values().forEach(run -> {
            run.control().requestCancel();
            run.control().stopStartWaiter();
        });
        events.closeSubscriptions();
        executor.shutdown();
        try {
            executor.awaitTermination(Duration.ofSeconds(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class SessionBusyException extends RuntimeException { }

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
