package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.web.agent.*;
import com.agentflow.web.run.*;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/** All write transactions lock the Run before accessing its invocation rows. */
public class ApprovalPersistence {
    private final AgentTaskRepository tasks;
    private final ToolInvocationRepository invocations;
    private final EntityManager entityManager;

    public ApprovalPersistence(AgentTaskRepository tasks, ToolInvocationRepository invocations, EntityManager entityManager) {
        this.tasks = tasks; this.invocations = invocations; this.entityManager = entityManager;
    }

    @Transactional(timeout = 3)
    public ApprovalSnapshot create(ApprovalRequest request) {
        var call = request.preparedCall();
        var task = owned(call.ownerId(), call.runId(), true);
        var existing = invocations.findByTaskIdAndCallId(task.getId(), call.callId());
        if (existing.isPresent()) {
            var snapshot = existing.get().snapshot();
            verifyBinding(snapshot, request);
            return snapshot;
        }
        var rows = invocations.findByTaskIdOrderByCreatedAtAscIdAsc(task.getId());
        if (rows.size() >= 8 || rows.stream().anyMatch(row -> "PENDING".equals(row.getApprovalStatus())))
            throw new IllegalStateException("approval capacity exceeded");
        task.waitForApproval(request.createdAt());
        var row = ToolInvocationEntity.pending(request, task.getUserId());
        entityManager.persist(row); entityManager.flush();
        return row.snapshot();
    }

    @Transactional(readOnly = true, timeout = 3)
    public List<ApprovalSnapshot> listOwned(String ownerId, String taskId) {
        owned(ownerId, taskId, false);
        return invocations.findByTaskIdOrderByCreatedAtAscIdAsc(taskId).stream()
                .filter(row -> row.getApprovalStatus() != null).map(ToolInvocationEntity::snapshot).toList();
    }

    @Transactional(readOnly = true, timeout = 3)
    public ApprovalSnapshot getOwned(String ownerId, String taskId, String approvalId) {
        owned(ownerId, taskId, false);
        return row(taskId, approvalId).snapshot();
    }

    @Transactional(timeout = 3)
    public ApprovalSnapshot resolve(String ownerId, String taskId, String approvalId, ApprovalStatus status,
                                    ApprovalResolution.DecisionSource source, Instant at, long waitMillis) {
        var task = owned(ownerId, taskId, true);
        var row = row(taskId, approvalId);
        if ("PENDING".equals(row.getApprovalStatus())) {
            if (source == ApprovalResolution.DecisionSource.USER &&
                    (!"WAITING_APPROVAL".equals(task.getStatus()) || task.isCancelRequested()))
                throw new ApprovalService.ConflictException("APPROVAL_NOT_PENDING");
            row.resolve(status, source, at, waitMillis);
        }
        entityManager.flush();
        return row.snapshot();
    }

    @Transactional(timeout = 3)
    public ApprovalSnapshot dispatch(ApprovalRequest request, Instant at) {
        var task = owned(request.preparedCall().ownerId(), request.preparedCall().runId(), true);
        var row = row(task.getId(), request.approvalId().toString());
        verifyBinding(row.snapshot(), request);
        if (row.snapshot().status() != ApprovalStatus.APPROVED || row.snapshot().dispatchCount() != 0)
            throw new ApprovalService.ConflictException("APPROVAL_NOT_PENDING");
        task.resumeFromApproval(at); row.markDispatch(at); entityManager.flush();
        return row.snapshot();
    }

    private AgentTaskEntity owned(String ownerId, String taskId, boolean lock) {
        return (lock ? tasks.findByIdForUpdate(taskId) : tasks.findById(taskId))
                .filter(task -> ownerId.equals(task.getUserId())).orElseThrow(RunCoordinator.RunNotFoundException::new);
    }

    private ToolInvocationEntity row(String taskId, String approvalId) {
        return invocations.findById(approvalId).filter(row -> taskId.equals(row.getTaskId()) && row.getApprovalStatus() != null)
                .orElseThrow(RunCoordinator.RunNotFoundException::new);
    }

    static void verifyBinding(ApprovalSnapshot snapshot, ApprovalRequest request) {
        var call = request.preparedCall();
        if (!snapshot.approvalId().equals(request.approvalId().toString()) || !snapshot.taskId().equals(call.runId())
                || !snapshot.callId().equals(call.callId()) || !snapshot.toolName().equals(call.call().name())
                || !snapshot.argumentsDigest().equals(call.argumentsDigest())
                || !snapshot.definitionVersion().equals(call.toolDefinitionVersion())
                || !snapshot.policyVersion().equals(request.policyDecision().policyVersion())
                || !snapshot.createdAt().equals(request.createdAt()) || !snapshot.expiresAt().equals(request.expiresAt()))
            throw new IllegalArgumentException("approval binding mismatch");
    }
}
