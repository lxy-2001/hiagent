package com.agentflow.web.run;

import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentSessionEntity;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentStepEntity;
import com.agentflow.web.agent.AgentTaskEntity;
import com.agentflow.web.agent.AgentTaskRepository;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;

public class RunPersistence {

    private final AgentSessionRepository sessions;
    private final AgentTaskRepository tasks;
    private final AgentStepRepository steps;
    private final EntityManager entityManager;

    public RunPersistence(AgentSessionRepository sessions, AgentTaskRepository tasks,
                          AgentStepRepository steps, EntityManager entityManager) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
        this.steps = Objects.requireNonNull(steps, "steps must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Transactional(readOnly = true, timeout = 3)
    public boolean ownsSession(String userId, String sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId).isPresent();
    }

    @Transactional(timeout = 3)
    public RunSnapshot createQueued(CreateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AgentSessionEntity session;
        if (command.createSession()) {
            session = new AgentSessionEntity(command.sessionId(), command.userId(), command.title(), command.createdAt());
            entityManager.persist(session);
        } else {
            session = sessions.findOwnedForUpdate(command.sessionId(), command.userId())
                    .orElseThrow(() -> new CreateRejectedException("NOT_FOUND"));
        }
        long sequence;
        try { sequence = session.allocateTurnSequence(); }
        catch (ArithmeticException exhausted) { throw new CreateRejectedException("TURN_SEQUENCE_EXHAUSTED"); }
        AgentTaskEntity task = new AgentTaskEntity(command.taskId(), command.sessionId(),
                command.userId(), command.input(), RunLifecycleStatus.QUEUED.name(),
                command.createdAt(), sequence, command.requireEvidence());
        entityManager.persist(task);
        entityManager.flush();
        return snapshot(task);
    }

    @Transactional(timeout = 3)
    public StartResult markRunning(String taskId, Instant startedAt) {
        requireNonBlank(taskId, "taskId");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        AgentTaskEntity task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null) {
            return new StartResult(StartOutcome.MISSING, null);
        }
        RunLifecycleStatus status = RunLifecycleStatus.valueOf(task.getStatus());
        if (status.isTerminal()) {
            return new StartResult(StartOutcome.TERMINAL, snapshot(task));
        }
        if (status == RunLifecycleStatus.RUNNING) {
            return new StartResult(StartOutcome.ALREADY_RUNNING, snapshot(task));
        }
        task.markRunning(startedAt);
        entityManager.flush();
        return new StartResult(StartOutcome.STARTED, snapshot(task));
    }

    public enum StartOutcome { STARTED, ALREADY_RUNNING, TERMINAL, MISSING }

    public record StartResult(StartOutcome outcome, RunSnapshot snapshot) {
    }

    @Transactional(timeout = 3)
    public CommittedTerminal complete(RunResultProjector.FinalProjection projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        AgentTaskEntity task = tasks.findByIdForUpdate(projection.taskId()).orElse(null);
        if (task == null) {
            throw new IllegalStateException("run does not exist");
        }
        if (RunLifecycleStatus.valueOf(task.getStatus()).isTerminal()) {
            RunSnapshot actual = snapshot(task);
            return new CommittedTerminal(actual, false, !matches(actual, projection));
        }

        mergeInvocations(task, projection);
        List<RunResultProjector.ProjectedStep> finalSteps = mergeSteps(projection);
        steps.deleteByTaskId(projection.taskId());
        entityManager.flush();
        for (RunResultProjector.ProjectedStep step : finalSteps) {
            entityManager.persist(new AgentStepEntity(step, projection.finishedAt()));
        }
        task.applyFinal(projection);
        entityManager.flush();
        return new CommittedTerminal(snapshot(task), true, false);
    }

    public record CommittedTerminal(RunSnapshot snapshot, boolean written, boolean conflict) {
    }

    @Transactional(timeout = 3)
    public boolean recordStep(RunResultProjector.ProjectedStep step) {
        Objects.requireNonNull(step, "step must not be null");
        AgentTaskEntity task = tasks.findByIdForUpdate(step.taskId()).orElse(null);
        if (task == null || RunLifecycleStatus.valueOf(task.getStatus()).isTerminal()) {
            return false;
        }
        AgentStepEntity entity = steps.findByTaskIdAndStepNo(step.taskId(), step.stepNo())
                .orElseGet(() -> new AgentStepEntity(step, Instant.now()));
        entity.apply(step);
        steps.saveAndFlush(entity);
        return true;
    }

    @Transactional(timeout = 3)
    public CancelResult requestCancellation(String taskId, Instant requestedAt) {
        requireNonBlank(taskId, "taskId");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        AgentTaskEntity task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null) return new CancelResult(CancelOutcome.MISSING, null);
        if (RunLifecycleStatus.valueOf(task.getStatus()).isTerminal()) {
            return new CancelResult(CancelOutcome.TERMINAL, snapshot(task));
        }
        task.requestCancellation(requestedAt);
        entityManager.flush();
        return new CancelResult(CancelOutcome.RECORDED, snapshot(task));
    }

    public enum CancelOutcome { RECORDED, TERMINAL, MISSING }
    public record CancelResult(CancelOutcome outcome, RunSnapshot snapshot) { }

    @Transactional(readOnly = true, timeout = 3)
    public Optional<RunSnapshot> getOwned(String userId, String taskId) {
        requireNonBlank(userId, "userId");
        requireNonBlank(taskId, "taskId");
        Long bytes = tasks.ownedCitationBytes(taskId, userId);
        if (bytes == null) { return Optional.empty(); }
        if (bytes > CitationSnapshotCodec.MAX_BYTES) { throw new CitationSnapshotCodec.UnavailableException(); }
        return tasks.findById(taskId).filter(task -> userId.equals(task.getUserId()))
                .map(RunPersistence::snapshot);
    }

    @Transactional(readOnly = true, timeout = 3)
    public List<RunResultProjector.ProjectedStep> getOwnedSteps(String userId, String taskId) {
        if (getOwned(userId, taskId).isEmpty()) return List.of();
        return steps.findTop256ByTaskIdOrderByStepNoAsc(taskId).stream()
                .map(AgentStepEntity::toProjection).toList();
    }

    @Transactional(timeout = 3)
    public List<RunSnapshot> convergeInterrupted(String afterId, int limit, Instant finishedAt) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        List<AgentTaskEntity> found = tasks.findInterruptedAfter(List.of(
                RunLifecycleStatus.QUEUED.name(), RunLifecycleStatus.RUNNING.name()),
                afterId == null ? "" : afterId, PageRequest.of(0, limit));
        RunResultProjector projector = new RunResultProjector();
        for (AgentTaskEntity task : found) {
            task.applyFinal(projector.projectFailure(task.getId(),
                    RunResultProjector.FailureKind.PROCESS_INTERRUPTED, finishedAt, task.isCancelRequested()));
        }
        entityManager.flush();
        return found.stream().map(RunPersistence::snapshot).toList();
    }

    private void mergeInvocations(AgentTaskEntity task, RunResultProjector.FinalProjection projection) {
        for (var record : projection.toolInvocations()) {
            var rows = entityManager.createQuery("select i from ToolInvocationEntity i where i.taskId = :task and i.callId = :call",
                    com.agentflow.web.approval.ToolInvocationEntity.class)
                    .setParameter("task", task.getId()).setParameter("call", record.callId()).getResultList();
            if (rows.isEmpty()) {
                entityManager.persist(com.agentflow.web.approval.ToolInvocationEntity.completed(task.getId(), task.getUserId(), record));
            } else {
                rows.get(0).merge(record);
            }
        }
    }

    private List<RunResultProjector.ProjectedStep> mergeSteps(
            RunResultProjector.FinalProjection projection) {
        if (projection.recordingComplete()) {
            return projection.steps();
        }
        Map<Integer, RunResultProjector.ProjectedStep> merged = new LinkedHashMap<>();
        for (AgentStepEntity existing : steps.findTop256ByTaskIdOrderByStepNoAsc(
                projection.taskId())) {
            merged.put(existing.getStepNo(), existing.toProjection());
        }
        for (RunResultProjector.ProjectedStep returned : projection.steps()) {
            merged.put(returned.stepNo(), returned);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingInt(RunResultProjector.ProjectedStep::stepNo))
                .limit(256)
                .toList();
    }

    private static boolean matches(RunSnapshot actual,
                                   RunResultProjector.FinalProjection projection) {
        return actual.status() == projection.status()
                && actual.terminationReason() == projection.terminationReason()
                && actual.runtimeReason() == projection.runtimeReason()
                && Objects.equals(actual.finalAnswer(), projection.finalAnswer())
                && actual.citations().equals(projection.citations())
                && Objects.equals(actual.usage(), projection.usage())
                && Objects.equals(actual.finishedAt(), projection.finishedAt())
                && actual.cancelRequested() == projection.cancelRequested()
                && actual.recordingComplete() == projection.recordingComplete();
    }

    /** Definite domain rejection: no uncertain database commit needs recovery. */
    public static final class CreateRejectedException extends RuntimeException {
        private final String code;
        public CreateRejectedException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    public record CreateCommand(String taskId, String sessionId, String userId, String input,
                                String title, Instant createdAt, boolean createSession, boolean requireEvidence) {
        public CreateCommand(String taskId, String sessionId, String userId, String input, String title, Instant createdAt, boolean createSession) {
            this(taskId, sessionId, userId, input, title, createdAt, createSession, false);
        }
        public CreateCommand(String taskId, String sessionId, String userId, String input, String title, Instant createdAt) {
            this(taskId, sessionId, userId, input, title, createdAt, true);
        }
        public CreateCommand {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(userId, "userId");
            requireNonBlank(input, "input");
            requireNonBlank(title, "title");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    private static RunSnapshot snapshot(AgentTaskEntity task) {
        com.agentflow.core.chat.TokenUsage usage = task.getTotalTokens() == null ? null
                : new com.agentflow.core.chat.TokenUsage(task.getPromptTokens(),
                task.getCompletionTokens(), task.getTotalTokens());
        return new RunSnapshot(task.getId(), task.getId(), task.getSessionId(),
                RunLifecycleStatus.valueOf(task.getStatus()), task.getUserInput(),
                task.getFinalAnswer(), task.getCreatedAt(), task.getUpdatedAt(),
                task.getStartedAt(), task.getFinishedAt(), task.isCancelRequested(),
                task.getTerminationReason() == null ? null
                        : RunTerminationReason.valueOf(task.getTerminationReason()),
                task.getRuntimeReason() == null ? null
                        : com.agentflow.core.runtime.TerminationReason.valueOf(task.getRuntimeReason()),
                task.getErrorCode(), task.isRecordingComplete(), usage, task.isRequireEvidence(),
                new CitationSnapshotCodec().decode(task.getCitationsJson()));
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
