package com.agentflow.web.run;

import com.agentflow.core.cancel.CancellationSignal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunControl implements CancellationSignal {

    public enum Phase {
        PREPARING,
        QUEUED,
        STARTING,
        EXECUTING,
        FINALIZING,
        RELEASED
    }

    public enum StartClaim {
        START,
        EXPIRED,
        SKIP
    }

    public enum RuntimeCallClaim {
        CALL,
        CANCEL_BEFORE_CALL,
        SKIP
    }

    public enum CancelClaim {
        CANCEL_BEFORE_START,
        SIGNALLED,
        ALREADY_REQUESTED,
        TERMINAL
    }

    public enum StartResolution {
        RUNNING,
        TERMINAL,
        STOPPED
    }

    public enum PendingKind {
        CREATE_UNCERTAIN,
        START_UNCERTAIN,
        CANCEL_FLAG_PENDING,
        FINAL_PENDING
    }

    public enum PendingOutcome {
        RETRY,
        CONFIRMED,
        TERMINAL_CONFIRMED
    }

    public record PendingClaim(long claimId, PendingKind kind, long revision, Object payload) {
    }

    public record PendingView(PendingKind kind, long revision, Object payload) {
    }

    private final String taskId;
    private final String userId;
    private final long acceptedTick;
    private final long queueDeadline;
    private final AtomicBoolean cancellationSignal = new AtomicBoolean();

    private Phase phase;
    private boolean runtimeCallClaimed;
    private long pendingRevision;
    private long claimSequence;
    private PendingView pending;
    private PendingClaim ioClaim;
    private Long approvalIoClaim;
    private boolean finalFrozen;
    private boolean workerEntered;
    private boolean workerExited;
    private boolean queueDetached;
    private boolean terminalConfirmed;
    private boolean releaseClaimed;
    private StartResolution startResolution;

    private RunControl(String taskId, String userId, long acceptedTick, long queueDeadline) {
        this.taskId = requireNonBlank(taskId, "taskId");
        this.userId = requireNonBlank(userId, "userId");
        if (queueDeadline - acceptedTick <= 0) {
            throw new IllegalArgumentException("queueDeadline must be after acceptedTick");
        }
        this.acceptedTick = acceptedTick;
        this.queueDeadline = queueDeadline;
        this.phase = Phase.QUEUED;
    }

    public static RunControl queued(String taskId, String userId, long acceptedTick, long queueDeadline) {
        return new RunControl(taskId, userId, acceptedTick, queueDeadline);
    }

    public synchronized StartClaim claimStart(long nowTick) {
        if (phase != Phase.QUEUED) {
            return StartClaim.SKIP;
        }
        if (nowTick - queueDeadline >= 0) {
            phase = Phase.FINALIZING;
            return StartClaim.EXPIRED;
        }
        phase = Phase.STARTING;
        return StartClaim.START;
    }

    public synchronized RuntimeCallClaim claimRuntimeCall(boolean startCommitConfirmed) {
        if (phase != Phase.STARTING || !startCommitConfirmed || runtimeCallClaimed) {
            return RuntimeCallClaim.SKIP;
        }
        if (cancellationSignal.get()) {
            phase = Phase.FINALIZING;
            return RuntimeCallClaim.CANCEL_BEFORE_CALL;
        }
        runtimeCallClaimed = true;
        phase = Phase.EXECUTING;
        return RuntimeCallClaim.CALL;
    }

    public synchronized CancelClaim requestCancel() {
        if (phase == Phase.FINALIZING || phase == Phase.RELEASED) {
            return CancelClaim.TERMINAL;
        }
        if (cancellationSignal.getAndSet(true)) {
            return CancelClaim.ALREADY_REQUESTED;
        }
        return switch (phase) {
            case QUEUED -> {
                phase = Phase.FINALIZING;
                yield CancelClaim.CANCEL_BEFORE_START;
            }
            case PREPARING, STARTING, EXECUTING -> CancelClaim.SIGNALLED;
            case FINALIZING, RELEASED -> throw new IllegalStateException("terminal phase changed while locked");
        };
    }

    public synchronized long markCancellationPending() {
        if (finalFrozen) {
            return pendingRevision;
        }
        if (pending != null && pending.kind() == PendingKind.CANCEL_FLAG_PENDING) {
            return pending.revision();
        }
        pendingRevision = Math.incrementExact(pendingRevision);
        pending = new PendingView(PendingKind.CANCEL_FLAG_PENDING, pendingRevision, null);
        return pendingRevision;
    }

    public synchronized long markCreateUncertain(Object command) {
        Objects.requireNonNull(command, "command must not be null");
        if (pending != null || (ioClaim != null || approvalIoClaim != null) || finalFrozen || phase != Phase.QUEUED) {
            throw new IllegalStateException("create uncertainty can only be recorded once before dispatch");
        }
        phase = Phase.PREPARING;
        pendingRevision = Math.incrementExact(pendingRevision);
        pending = new PendingView(PendingKind.CREATE_UNCERTAIN, pendingRevision, command);
        return pendingRevision;
    }

    public synchronized long markStartUncertain(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (pending != null || (ioClaim != null || approvalIoClaim != null) || finalFrozen || phase != Phase.STARTING) {
            throw new IllegalStateException("start uncertainty can only be recorded before the runtime call");
        }
        pendingRevision = Math.incrementExact(pendingRevision);
        pending = new PendingView(PendingKind.START_UNCERTAIN, pendingRevision, startedAt);
        return pendingRevision;
    }

    public synchronized StartResolution awaitStartResolution() {
        while (startResolution == null) {
            try {
                wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return StartResolution.STOPPED;
            }
        }
        return startResolution;
    }

    public synchronized boolean finishStartPending(PendingClaim claim, StartResolution resolution) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
        if (claim.kind() != PendingKind.START_UNCERTAIN || resolution == StartResolution.STOPPED) {
            throw new IllegalArgumentException("start claim requires a persisted start resolution");
        }
        boolean remains = finishPending(claim, resolution == StartResolution.TERMINAL
                ? PendingOutcome.TERMINAL_CONFIRMED : PendingOutcome.CONFIRMED);
        if (!remains && startResolution == null) {
            startResolution = resolution;
            notifyAll();
        }
        return remains;
    }

    public synchronized void stopStartWaiter() {
        if (startResolution == null && (pending != null && pending.kind() == PendingKind.START_UNCERTAIN
                || ioClaim != null && ioClaim.kind() == PendingKind.START_UNCERTAIN)) {
            startResolution = StartResolution.STOPPED;
            notifyAll();
        }
    }

    /** Shares the same exclusion boundary as cancellation and final persistence. No IO under this monitor. */
    public synchronized Optional<Long> claimApprovalIo() {
        if (phase != Phase.EXECUTING || finalFrozen || pending != null || ioClaim != null || approvalIoClaim != null)
            return Optional.empty();
        approvalIoClaim = ++claimSequence;
        return Optional.of(approvalIoClaim);
    }

    public synchronized boolean finishApprovalIo(long claim, boolean dispatch) {
        if (approvalIoClaim == null || approvalIoClaim != claim) return false;
        approvalIoClaim = null;
        return !dispatch || phase == Phase.EXECUTING && !finalFrozen && !cancellationSignal.get();
    }

    public synchronized Optional<PendingClaim> claimCancellationWrite() {
        if (phase == Phase.STARTING || finalFrozen || (ioClaim != null || approvalIoClaim != null)
                || pending != null && pending.kind() != PendingKind.CANCEL_FLAG_PENDING) {
            return Optional.empty();
        }
        markCancellationPending();
        return claimPending();
    }

    public synchronized void markCreateConfirmed() {
        if (phase != Phase.PREPARING || pending != null || (ioClaim != null || approvalIoClaim != null)) {
            throw new IllegalStateException("create cannot be confirmed while its intent is pending");
        }
        phase = Phase.QUEUED;
    }

    public synchronized long freezeFinal(Object projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        if (finalFrozen) {
            return pendingRevision;
        }
        finalFrozen = true;
        phase = Phase.FINALIZING;
        pendingRevision = Math.incrementExact(pendingRevision);
        pending = new PendingView(PendingKind.FINAL_PENDING, pendingRevision, projection);
        return pendingRevision;
    }

    public synchronized Optional<PendingClaim> claimPending() {
        if (pending == null || (ioClaim != null || approvalIoClaim != null)) {
            return Optional.empty();
        }
        claimSequence = Math.incrementExact(claimSequence);
        ioClaim = new PendingClaim(claimSequence, pending.kind(), pending.revision(), pending.payload());
        return Optional.of(ioClaim);
    }

    public synchronized boolean finishPending(PendingClaim claim, PendingOutcome outcome) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (ioClaim == null || ioClaim.claimId() != claim.claimId()) {
            return pending != null;
        }
        ioClaim = null;
        if (outcome == PendingOutcome.TERMINAL_CONFIRMED) {
            terminalConfirmed = true;
        }
        if (outcome != PendingOutcome.RETRY && pending != null
                && pending.revision() == claim.revision()) {
            pending = null;
        }
        return pending != null;
    }

    public synchronized Optional<PendingView> pending() {
        return Optional.ofNullable(pending);
    }

    public synchronized void markWorkerEntered() {
        if (queueDetached || workerExited || releaseClaimed) {
            throw new IllegalStateException("detached or completed worker cannot enter");
        }
        workerEntered = true;
    }

    public synchronized void markWorkerExited() {
        if (!workerEntered) {
            throw new IllegalStateException("worker cannot exit before entering");
        }
        workerExited = true;
    }

    public synchronized void markExecutorSlotReleased() {
        if (workerEntered) {
            workerExited = true;
        } else {
            queueDetached = true;
        }
    }

    public synchronized void markQueueDetached() {
        if (workerEntered) {
            throw new IllegalStateException("entered worker cannot be detached from queue");
        }
        queueDetached = true;
    }

    public synchronized void markTerminalConfirmed() {
        terminalConfirmed = true;
    }

    public synchronized boolean claimRelease() {
        if (releaseClaimed || !terminalConfirmed || !(workerExited || queueDetached)) {
            return false;
        }
        releaseClaimed = true;
        phase = Phase.RELEASED;
        return true;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean runtimeCallClaimed() {
        return runtimeCallClaimed;
    }

    @Override
    public boolean isCancelled() {
        return cancellationSignal.get();
    }

    public String taskId() {
        return taskId;
    }

    public String userId() {
        return userId;
    }

    public long acceptedTick() {
        return acceptedTick;
    }

    public long queueDeadline() {
        return queueDeadline;
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
