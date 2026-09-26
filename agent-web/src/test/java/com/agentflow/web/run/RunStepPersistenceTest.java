package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepStatus;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.web.agent.AgentStepEntity;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskEntity;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = RunStepPersistenceTest.TestApplication.class)
class RunStepPersistenceTest {

    @Autowired RunPersistence persistence;
    @Autowired AgentStepRepository steps;
    private final RunResultProjector projector = new RunResultProjector();

    @Test
    void completeReturnedTraceReplacesRealtimeRowsAndDeletesExtras() {
        Instant created = createRunning("steps-1");
        assertThat(persistence.recordStep(project(step("steps-1", 1,
                AgentStepType.MODEL_DECISION, false, "realtime")))).isTrue();
        assertThat(persistence.recordStep(project(step("steps-1", 2,
                AgentStepType.TOOL_RESULT, false, "extra")))).isTrue();
        AgentResult result = AgentResult.success("steps-1", "done", List.of(
                step("steps-1", 1, AgentStepType.MODEL_DECISION, false, "returned"),
                step("steps-1", 2, AgentStepType.TERMINATION, true, "complete")),
                TokenUsage.empty());

        persistence.complete(projector.project("steps-1", result,
                created.plusSeconds(5), false, true));

        List<AgentStepEntity> stored = steps.findByTaskIdOrderByStepNoAsc("steps-1");
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getOutput()).isEqualTo("returned");
        assertThat(stored.get(1).getStepType()).isEqualTo("TERMINATION");
    }

    @Test
    void incompleteReturnedTraceMergesWithRealtimeRowsAndPrefersReturnedProjection() {
        Instant created = createRunning("steps-2");
        persistence.recordStep(project(step("steps-2", 1,
                AgentStepType.MODEL_DECISION, false, "realtime-one")));
        persistence.recordStep(project(step("steps-2", 2,
                AgentStepType.TOOL_RESULT, false, "realtime-two")));
        AgentResult incomplete = AgentResult.success("steps-2", "done", List.of(
                step("steps-2", 1, AgentStepType.MODEL_DECISION, false, "returned-one")),
                TokenUsage.empty());

        persistence.complete(projector.project("steps-2", incomplete,
                created.plusSeconds(5), false, true));

        List<AgentStepEntity> stored = steps.findByTaskIdOrderByStepNoAsc("steps-2");
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getOutput()).isEqualTo("returned-one");
        assertThat(stored.get(1).getOutput()).isEqualTo("realtime-two");
    }

    @Test
    void ignoresLateRealtimeWriteAfterTerminalCommit() {
        Instant created = createRunning("steps-3");
        AgentResult result = AgentResult.success("steps-3", "done", List.of(
                step("steps-3", 1, AgentStepType.TERMINATION, true, "done")), TokenUsage.empty());
        persistence.complete(projector.project("steps-3", result,
                created.plusSeconds(5), false, true));

        boolean accepted = persistence.recordStep(project(step("steps-3", 2,
                AgentStepType.TOOL_RESULT, false, "late")));

        assertThat(accepted).isFalse();
        assertThat(steps.findByTaskIdOrderByStepNoAsc("steps-3")).hasSize(1);
    }

    private Instant createRunning(String taskId) {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        persistence.createQueued(new RunPersistence.CreateCommand(taskId, "session-" + taskId,
                "user-1", "input", "title", created));
        persistence.markRunning(taskId, created.plusSeconds(1));
        return created;
    }

    private RunResultProjector.ProjectedStep project(AgentStepRecord step) {
        return projector.projectStep(step.taskId(), step).orElseThrow();
    }

    private static AgentStepRecord step(String taskId, int number, AgentStepType type,
                                        boolean terminal, String output) {
        return new AgentStepRecord(taskId, number, type, "name", "input", output,
                AgentStepStatus.SUCCESS, 1, 1, 1, null,
                "decision", "call", null, terminal);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {AgentWebAutoConfiguration.class, com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration.class})
    @EntityScan(basePackageClasses = AgentTaskEntity.class)
    @EnableJpaRepositories(basePackageClasses = AgentTaskRepository.class)
    @Import(RunPersistence.class)
    @Configuration(proxyBeanMethods = false)
    static class TestApplication {
    }
}
