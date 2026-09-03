package com.agentflow.web.agent;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.AgentTaskStatus;
import com.agentflow.web.support.Ids;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

@Service
public class AgentTaskService {

    private final AgentRuntime agentRuntime;
    private final AgentSessionRepository sessionRepository;
    private final AgentTaskRepository taskRepository;
    private final AgentStepRepository stepRepository;
    private final TaskEventPublisher eventPublisher;
    private final Executor applicationTaskExecutor;

    public AgentTaskService(AgentRuntime agentRuntime, AgentSessionRepository sessionRepository,
                            AgentTaskRepository taskRepository, AgentStepRepository stepRepository,
                            TaskEventPublisher eventPublisher, Executor applicationTaskExecutor) {
        this.agentRuntime = agentRuntime;
        this.sessionRepository = sessionRepository;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.eventPublisher = eventPublisher;
        this.applicationTaskExecutor = applicationTaskExecutor;
    }

    public AgentController.TaskResponse create(String userId, String input) {
        Instant now = Instant.now();
        String sessionId = Ids.newId();
        String taskId = Ids.newId();
        sessionRepository.save(new AgentSessionEntity(sessionId, userId, title(input), now));
        taskRepository.saveAndFlush(new AgentTaskEntity(taskId, sessionId, userId, input, AgentTaskStatus.RUNNING.name(), now));
        applicationTaskExecutor.execute(() -> execute(taskId));
        return new AgentController.TaskResponse(taskId, sessionId, AgentTaskStatus.RUNNING.name());
    }

    @Transactional(readOnly = true)
    public AgentController.TaskDetail getTask(String taskId) {
        AgentTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        return new AgentController.TaskDetail(task.getId(), task.getSessionId(), task.getStatus(),
                task.getUserInput(), task.getFinalAnswer());
    }

    @Transactional(readOnly = true)
    public List<AgentController.StepResponse> getSteps(String taskId) {
        return stepRepository.findByTaskIdOrderByStepNoAsc(taskId).stream()
                .map(step -> new AgentController.StepResponse(step.getStepNo(), step.getStepType(), step.getToolName(),
                        step.getStatus(), step.getOutput(), step.getErrorMessage()))
                .toList();
    }

    public SseEmitter subscribe(String taskId) {
        return eventPublisher.subscribe(taskId);
    }

    private void execute(String taskId) {
        try {
            AgentTaskEntity task = taskRepository.findById(taskId).orElseThrow();
            AgentResult result = agentRuntime.run(new AgentRequest(task.getId(), task.getSessionId(),
                    task.getUserId(), task.getUserInput()), eventPublisher::publish);
            if (result.status() == com.agentflow.core.runtime.RunStatus.SUCCEEDED
                    && result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
                task.complete(result.finalAnswer());
                taskRepository.save(task);
                eventPublisher.complete(taskId);
            } else {
                String reason = result.terminationReason() == null
                        ? "AGENT_RUNTIME_FAILED" : result.terminationReason().name();
                task.fail(reason);
                taskRepository.save(task);
                eventPublisher.error(taskId, new IllegalStateException(reason));
            }
        } catch (RuntimeException ex) {
            String safeMessage = safeFailureMessage(ex);
            taskRepository.findById(taskId).ifPresent(task -> {
                task.fail(safeMessage);
                taskRepository.save(task);
            });
            eventPublisher.error(taskId, new IllegalStateException(safeMessage));
        }
    }

    private String safeFailureMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "agent task failed" : throwable.getClass().getSimpleName();
        }
        String sanitized = message
                .replaceAll("(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+", "$1[redacted]")
                .replaceAll("(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+", "$1[redacted]");
        return sanitized.length() <= 1_024 ? sanitized : sanitized.substring(0, 1_024);
    }

    private String title(String input) {
        String trimmed = input == null ? "新任务" : input.strip();
        return trimmed.length() <= 48 ? trimmed : trimmed.substring(0, 48);
    }
}
