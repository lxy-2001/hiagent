package com.agentflow.web.agent;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {

    @Test
    void persistsCompletionOnlyForSuccessfulCoreResult() {
        AgentTaskEntity task = task("task-success");
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        TaskEventPublisher events = mock(TaskEventPublisher.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AgentRuntime runtime = runtimeReturning(AgentResult.success("task-success", "answer", List.of(),
                TokenUsage.empty()));
        AgentTaskService service = service(runtime, tasks, events, submitted);

        AgentController.TaskResponse response = service.create("user-1", "hello");
        when(tasks.findById(response.taskId())).thenReturn(Optional.of(task));
        submitted.get().run();

        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(task.getFinalAnswer()).isEqualTo("answer");
        verify(events).complete(response.taskId());
    }

    @Test
    void sanitizesUnexpectedRuntimeFailuresBeforePersistingOrPublishing() {
        AgentTaskEntity task = task("task-exception");
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        TaskEventPublisher events = mock(TaskEventPublisher.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public AgentResult run(com.agentflow.core.AgentRequest request,
                                   com.agentflow.core.AgentEventSink eventSink,
                                   com.agentflow.core.runtime.AgentRunOptions options) {
                throw new IllegalStateException("Bearer bearer-secret apiKey=secret-value token=another-secret");
            }
        };
        AgentTaskService service = service(runtime, tasks, events, submitted);

        AgentController.TaskResponse response = service.create("user-1", "hello");
        when(tasks.findById(response.taskId())).thenReturn(Optional.of(task));
        submitted.get().run();

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getFinalAnswer()).doesNotContain("bearer-secret").doesNotContain("secret-value").doesNotContain("another-secret");
        verify(events).error(eq(response.taskId()), any(IllegalStateException.class));
    }

    @Test
    void persistsFailureAndDoesNotCompleteWhenCoreResultTerminatesUnsuccessfully() {
        AgentTaskEntity task = task("task-failed");
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        TaskEventPublisher events = mock(TaskEventPublisher.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AgentRuntime runtime = runtimeReturning(AgentResult.failure("task-failed", RunStatus.BUDGET_EXCEEDED,
                TerminationReason.BUDGET_EXCEEDED, "budget exceeded", List.of(), TokenUsage.empty()));
        AgentTaskService service = service(runtime, tasks, events, submitted);

        AgentController.TaskResponse response = service.create("user-1", "hello");
        when(tasks.findById(response.taskId())).thenReturn(Optional.of(task));
        submitted.get().run();

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getFinalAnswer()).isEqualTo("BUDGET_EXCEEDED");
        verify(events).error(eq(response.taskId()), any(IllegalStateException.class));
    }

    private AgentTaskService service(AgentRuntime runtime, AgentTaskRepository tasks,
                                     TaskEventPublisher events, AtomicReference<Runnable> submitted) {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        AgentStepRepository steps = mock(AgentStepRepository.class);
        Executor executor = submitted::set;
        return new AgentTaskService(runtime, sessions, tasks, steps, events, executor);
    }

    private AgentRuntime runtimeReturning(AgentResult result) {
        return new AgentRuntime() {
            @Override
            public AgentResult run(com.agentflow.core.AgentRequest request,
                                   com.agentflow.core.AgentEventSink eventSink,
                                   com.agentflow.core.runtime.AgentRunOptions options) {
                return result;
            }
        };
    }

    private AgentTaskEntity task(String id) {
        return new AgentTaskEntity(id, "session-1", "user-1", "hello", "RUNNING", Instant.now());
    }
}
