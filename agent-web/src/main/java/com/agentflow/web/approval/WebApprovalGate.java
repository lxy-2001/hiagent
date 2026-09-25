package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.web.run.*;
import java.util.*;
import java.util.function.Consumer;

/** Waits on the original worker, without an open transaction or a held monitor. */
public final class WebApprovalGate implements ApprovalGate, AutoCloseable {
    private final ApprovalService service;
    private final RunControl run;
    private final Consumer<RunEvent.Draft> events;
    private final java.util.function.BooleanSupplier runExpired;
    private final Set<UUID> approvals = new HashSet<>();
    private final Set<UUID> dispatched = new HashSet<>();

    public WebApprovalGate(ApprovalService service, RunControl run, Consumer<RunEvent.Draft> events) {
        this(service, run, events, () -> false);
    }

    public WebApprovalGate(ApprovalService service, RunControl run, Consumer<RunEvent.Draft> events,
                           java.util.function.BooleanSupplier runExpired) {
        this.runExpired = Objects.requireNonNull(runExpired);
        this.service = Objects.requireNonNull(service); this.run = Objects.requireNonNull(run);
        this.events = Objects.requireNonNull(events);
    }

    @Override public ApprovalResolution await(ApprovalRequest request, ToolExecutionControl control) {
        if (!approvals.add(request.approvalId())) throw new IllegalStateException("approval already awaited");
        service.begin(request, run, control, events, runExpired);
        while (true) {
            var current = service.poll(run.userId(), run.taskId(), request.approvalId().toString());
            if (current.status() != ApprovalStatus.PENDING)
                return new ApprovalResolution(request.approvalId(), current.status(), current.decidedAt(),
                        current.decisionSource(), current.waitMillis());
            try { Thread.sleep(Math.max(1, Math.min(50, control.remainingTime().toMillis()))); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("approval wait interrupted");
            }
        }
    }

    @Override public boolean claimDispatch(ApprovalRequest request, ToolExecutionControl control) {
        if (!approvals.contains(request.approvalId()) || !dispatched.add(request.approvalId())
                || control.isCancelled() || control.remainingTime().isZero()) return false;
        return service.dispatch(request, control);
    }

    @Override public void close() {
        approvals.forEach(id -> service.release(id.toString()));
    }
}
