package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.approval.ApprovalResolution.DecisionSource;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.web.run.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/** Coordinates short committed writes; it never receives an executor or executes tools. */
public class ApprovalService {
    public enum Decision { APPROVE, REJECT }
    private record Active(RunControl run, ToolExecutionControl budget, long started,
                          Consumer<RunEvent.Draft> events, BooleanSupplier runExpired) { }
    private final ApprovalPersistence persistence;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    public ApprovalService(ApprovalPersistence persistence, Clock clock, LongSupplier nanos) {
        this.persistence = Objects.requireNonNull(persistence); this.clock = Objects.requireNonNull(clock);
        this.nanos = Objects.requireNonNull(nanos);
    }

    public ApprovalSnapshot begin(ApprovalRequest request, RunControl run, ToolExecutionControl budget,
                                   Consumer<RunEvent.Draft> events) {
        return begin(request, run, budget, events, () -> false);
    }

    public ApprovalSnapshot begin(ApprovalRequest request, RunControl run, ToolExecutionControl budget,
                                   Consumer<RunEvent.Draft> events, BooleanSupplier runExpired) {
        if (!run.taskId().equals(request.preparedCall().runId()) || !run.userId().equals(request.preparedCall().ownerId()))
            throw new IllegalArgumentException("approval owner mismatch");
        long claim = claim(run);
        try {
            budget.checkActive();
            ApprovalSnapshot snapshot;
            try { snapshot = persistence.create(request); }
            catch (RuntimeException uncertain) {
                snapshot = readBack(run, request.approvalId().toString(), budget,
                        value -> { ApprovalPersistence.verifyBinding(value, request); return true; });
            }
            var waiter = new Active(run, budget, nanos.getAsLong(), events, runExpired);
            if (active.putIfAbsent(snapshot.approvalId(), waiter) != null)
                throw new IllegalStateException("duplicate live approval");
            events.accept(new RunEvent.Draft(snapshot.taskId(), snapshot.runId(), RunEvent.Type.APPROVAL_REQUESTED,
                    clock.instant(), new Requested(snapshot.approvalId(), snapshot.callId(), snapshot.toolName(),
                    snapshot.risk().name(), snapshot.createdAt(), snapshot.expiresAt())));
            return snapshot;
        } finally { run.finishApprovalIo(claim, false); }
    }

    public List<ApprovalSnapshot> list(String owner, String task) {
        try { return persistence.listOwned(owner, task); }
        catch (RunCoordinator.RunNotFoundException missing) { throw missing; }
        catch (RuntimeException failure) { throw new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE", task); }
    }
    public ApprovalSnapshot get(String owner, String task, String approval) {
        try { return persistence.getOwned(owner, task, approval); }
        catch (RunCoordinator.RunNotFoundException missing) { throw missing; }
        catch (RuntimeException failure) { throw new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE", task); }
    }

    public ApprovalSnapshot decide(String owner, String task, String id, Decision decision) {
        var current = get(owner, task, id);
        ApprovalStatus requested = decision == Decision.APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        if (current.status() != ApprovalStatus.PENDING) return repeat(current, requested);
        var waiter = active.get(id);
        if (waiter == null) return repeat(get(owner, task, id), requested);
        long claim;
        try { claim = claim(waiter.run()); }
        catch (RunCoordinator.RunUnavailableException unavailable) {
            // Another request may have committed the decision and the worker already exited.
            // A read of that decision is safe; never retry the write or dispatch here.
            current = get(owner, task, id);
            if (current.status() != ApprovalStatus.PENDING) return repeat(current, requested);
            throw unavailable;
        }
        try {
            current = get(owner, task, id);
            if (current.status() != ApprovalStatus.PENDING) return repeat(current, requested);
            ApprovalStatus invalid = invalidation(waiter);
            if (invalid != null) {
                resolve(waiter, current, invalid, invalid == ApprovalStatus.CANCELLED ? DecisionSource.CANCEL : waiter.runExpired().getAsBoolean() ? DecisionSource.RUN_TIMEOUT : DecisionSource.TTL);
                throw new ConflictException("APPROVAL_NOT_PENDING");
            }
            return resolve(waiter, current, requested, DecisionSource.USER);
        } finally { waiter.run().finishApprovalIo(claim, false); }
    }

    public ApprovalSnapshot poll(String owner, String task, String id) {
        var current = get(owner, task, id);
        var waiter = active.get(id);
        if (current.status() != ApprovalStatus.PENDING || waiter == null) return current;
        var invalid = invalidation(waiter);
        if (invalid == null) return current;
        long claim = claim(waiter.run());
        try {
            current = get(owner, task, id);
            if (current.status() == ApprovalStatus.PENDING)
                return resolve(waiter, current, invalid, invalid == ApprovalStatus.CANCELLED ? DecisionSource.CANCEL : waiter.runExpired().getAsBoolean() ? DecisionSource.RUN_TIMEOUT : DecisionSource.TTL);
            return current;
        } finally { waiter.run().finishApprovalIo(claim, false); }
    }

    public boolean dispatch(ApprovalRequest request, ToolExecutionControl budget) {
        var waiter = active.get(request.approvalId().toString());
        if (waiter == null) return false;
        long claim = claim(waiter.run());
        boolean committed = false;
        try {
            if (invalidation(waiter) != null) return false;
            budget.checkActive();
            try { persistence.dispatch(request, clock.instant()); }
            catch (ConflictException conflict) { throw conflict; }
            catch (RuntimeException uncertain) {
                readBack(waiter.run(), request.approvalId().toString(), budget, value -> {
                    ApprovalPersistence.verifyBinding(value, request);
                    return value.status() == ApprovalStatus.APPROVED && value.dispatchCount() == 1;
                });
            }
            committed = true;
        } finally {
            boolean allowed = waiter.run().finishApprovalIo(claim, committed);
            if (committed && !allowed) committed = false;
        }
        return committed && !budget.isCancelled() && !budget.remainingTime().isZero();
    }

    public void release(String id) { active.remove(id); }

    private ApprovalSnapshot resolve(Active waiter, ApprovalSnapshot current, ApprovalStatus status, DecisionSource source) {
        ApprovalSnapshot saved;
        try {
            saved = persistence.resolve(waiter.run().userId(), current.taskId(), current.approvalId(), status,
                    source, clock.instant(), Math.max(0, nanos.getAsLong() - waiter.started()) / 1_000_000);
        } catch (ConflictException conflict) { throw conflict; }
        catch (RuntimeException uncertain) {
            saved = readBack(waiter.run(), current.approvalId(), waiter.budget(), value ->
                    value.status() == status && value.decisionSource() == source);
        }
        waiter.events().accept(new RunEvent.Draft(saved.taskId(), saved.runId(), RunEvent.Type.APPROVAL_RESOLVED,
                clock.instant(), new Resolved(saved.approvalId(), saved.callId(), saved.status().name(),
                saved.decisionSource().name(), saved.decidedAt(), saved.waitMillis())));
        return saved;
    }

    private ApprovalSnapshot readBack(RunControl run, String id, ToolExecutionControl budget,
                                      Predicate<ApprovalSnapshot> confirmed) {
        // The original IO claim remains held across bounded reads. Never reissue a dispatch write.
        long end = System.nanoTime() + Math.min(java.util.concurrent.TimeUnit.SECONDS.toNanos(3), budget.remainingTime().toNanos());
        do {
            try {
                var snapshot = persistence.getOwned(run.userId(), run.taskId(), id);
                if (confirmed.test(snapshot)) return snapshot;
            } catch (RuntimeException ignored) { /* no exception text may escape */ }
            if (run.isCancelled() || System.nanoTime() - end >= 0) break;
            try { Thread.sleep(25); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        } while (true);
        run.markApprovalUncertain();
        throw new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE", run.taskId());
    }

    private static ApprovalStatus invalidation(Active waiter) {
        if (waiter.run().isCancelled()) return ApprovalStatus.CANCELLED;
        if (waiter.runExpired().getAsBoolean() || waiter.budget().remainingTime().isZero()) return ApprovalStatus.EXPIRED;
        return null;
    }
    private static ApprovalSnapshot repeat(ApprovalSnapshot current, ApprovalStatus wanted) {
        if ((current.status() == ApprovalStatus.APPROVED || current.status() == ApprovalStatus.REJECTED)
                && current.decisionSource() == DecisionSource.USER) {
            if (current.status() == wanted) return current;
            throw new ConflictException("APPROVAL_ALREADY_DECIDED");
        }
        throw new ConflictException("APPROVAL_NOT_PENDING");
    }
    private static long claim(RunControl run) {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        do {
            var claim = run.claimApprovalIo();
            if (claim.isPresent()) return claim.get();
            if (run.isApprovalUncertain() || run.phase() != RunControl.Phase.EXECUTING || run.pending().isPresent()) break;
            try { Thread.sleep(5); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        } while (System.nanoTime() - end < 0);
        throw new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE", run.taskId());
    }
    public static final class ConflictException extends RuntimeException {
        private final String code;
        public ConflictException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    public record Requested(String approvalId, String callId, String toolName, String risk, Instant createdAt, Instant expiresAt) { }
    public record Resolved(String approvalId, String callId, String status, String decisionSource, Instant resolvedAt, Long waitMillis) { }
}
