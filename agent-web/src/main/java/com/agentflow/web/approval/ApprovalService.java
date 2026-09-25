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
                          Consumer<RunEvent.Draft> events) { }
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
        if (!run.taskId().equals(request.preparedCall().runId()) || !run.userId().equals(request.preparedCall().ownerId()))
            throw new IllegalArgumentException("approval owner mismatch");
        long claim = claim(run);
        try {
            budget.checkActive();
            var snapshot = persistence.create(request);
            var waiter = new Active(run, budget, nanos.getAsLong(), events);
            if (active.putIfAbsent(snapshot.approvalId(), waiter) != null)
                throw new IllegalStateException("duplicate live approval");
            events.accept(new RunEvent.Draft(snapshot.taskId(), snapshot.runId(), RunEvent.Type.APPROVAL_REQUESTED,
                    clock.instant(), new Requested(snapshot.approvalId(), snapshot.callId(), snapshot.toolName(),
                    snapshot.risk().name(), snapshot.createdAt(), snapshot.expiresAt())));
            return snapshot;
        } finally { run.finishApprovalIo(claim, false); }
    }

    public List<ApprovalSnapshot> list(String owner, String task) { return persistence.listOwned(owner, task); }
    public ApprovalSnapshot get(String owner, String task, String approval) { return persistence.getOwned(owner, task, approval); }

    public ApprovalSnapshot decide(String owner, String task, String id, Decision decision) {
        var current = persistence.getOwned(owner, task, id);
        ApprovalStatus requested = decision == Decision.APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        if (current.status() != ApprovalStatus.PENDING) return repeat(current, requested);
        var waiter = active.get(id);
        if (waiter == null) throw new ConflictException("APPROVAL_NOT_PENDING");
        long claim = claim(waiter.run());
        try {
            current = persistence.getOwned(owner, task, id);
            if (current.status() != ApprovalStatus.PENDING) return repeat(current, requested);
            ApprovalStatus invalid = invalidation(waiter);
            if (invalid != null) {
                resolve(waiter, current, invalid, invalid == ApprovalStatus.CANCELLED ? DecisionSource.CANCEL : DecisionSource.TTL);
                throw new ConflictException("APPROVAL_NOT_PENDING");
            }
            return resolve(waiter, current, requested, DecisionSource.USER);
        } finally { waiter.run().finishApprovalIo(claim, false); }
    }

    public ApprovalSnapshot poll(String owner, String task, String id) {
        var current = persistence.getOwned(owner, task, id);
        var waiter = active.get(id);
        if (current.status() != ApprovalStatus.PENDING || waiter == null) return current;
        var invalid = invalidation(waiter);
        if (invalid == null) return current;
        long claim = claim(waiter.run());
        try {
            current = persistence.getOwned(owner, task, id);
            if (current.status() == ApprovalStatus.PENDING)
                return resolve(waiter, current, invalid, invalid == ApprovalStatus.CANCELLED ? DecisionSource.CANCEL : DecisionSource.TTL);
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
            persistence.dispatch(request, clock.instant());
            committed = true;
        } finally {
            boolean allowed = waiter.run().finishApprovalIo(claim, committed);
            if (committed && !allowed) committed = false;
        }
        return committed && !budget.isCancelled() && !budget.remainingTime().isZero();
    }

    public void release(String id) { active.remove(id); }

    private ApprovalSnapshot resolve(Active waiter, ApprovalSnapshot current, ApprovalStatus status, DecisionSource source) {
        var saved = persistence.resolve(waiter.run().userId(), current.taskId(), current.approvalId(), status,
                source, clock.instant(), Math.max(0, nanos.getAsLong() - waiter.started()) / 1_000_000);
        waiter.events().accept(new RunEvent.Draft(saved.taskId(), saved.runId(), RunEvent.Type.APPROVAL_RESOLVED,
                clock.instant(), new Resolved(saved.approvalId(), saved.callId(), saved.status().name(),
                saved.decisionSource().name(), saved.decidedAt(), saved.waitMillis())));
        return saved;
    }

    private static ApprovalStatus invalidation(Active waiter) {
        if (waiter.run().isCancelled()) return ApprovalStatus.CANCELLED;
        if (waiter.budget().remainingTime().isZero()) return ApprovalStatus.EXPIRED;
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
            if (run.phase() != RunControl.Phase.EXECUTING || run.pending().isPresent()) break;
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
