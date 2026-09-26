package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepStatus;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
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
@ContextConfiguration(classes = RunFinalTransactionTest.TestApplication.class)
class RunFinalTransactionTest {

    @Autowired RunPersistence persistence;
    @Autowired AgentTaskRepository tasks;
    @Autowired AgentStepRepository steps;
    private final RunResultProjector projector = new RunResultProjector();

    @Test
    void commitsStepsAndTerminalFactsInOneTransaction() {
        Instant created = createRunning("final-1");
        TokenUsage usage = new TokenUsage(5, 3, 8);
        AgentResult result = AgentResult.success("final-1", "answer", List.of(
                step("final-1", 1, AgentStepType.MODEL_DECISION, false),
                step("final-1", 2, AgentStepType.TERMINATION, true)), usage);
        RunResultProjector.FinalProjection projection = projector.project(
                "final-1", result, created.plusSeconds(5), false, true);

        RunPersistence.CommittedTerminal committed = persistence.complete(projection);

        assertThat(committed.written()).isTrue();
        assertThat(committed.conflict()).isFalse();
        assertThat(committed.snapshot().status()).isEqualTo(RunLifecycleStatus.SUCCEEDED);
        assertThat(committed.snapshot().finalAnswer()).isEqualTo("answer");
        assertThat(committed.snapshot().usage()).isEqualTo(usage);
        assertThat(committed.snapshot().recordingComplete()).isTrue();
        assertThat(steps.findByTaskIdOrderByStepNoAsc("final-1")).hasSize(2);
    }

    @Test
    void existingTerminalFactWinsAndCannotBeOverwritten() {
        Instant created = createRunning("final-2");
        RunResultProjector.FinalProjection success = projector.project("final-2",
                AgentResult.success("final-2", "first", List.of(
                        step("final-2", 1, AgentStepType.TERMINATION, true)), TokenUsage.empty()),
                created.plusSeconds(5), false, true);
        persistence.complete(success);
        RunResultProjector.FinalProjection competing = projector.projectFailure("final-2",
                RunResultProjector.FailureKind.INTERNAL_ERROR, created.plusSeconds(9), true);

        RunPersistence.CommittedTerminal committed = persistence.complete(competing);

        assertThat(committed.written()).isFalse();
        assertThat(committed.conflict()).isTrue();
        assertThat(committed.snapshot().status()).isEqualTo(RunLifecycleStatus.SUCCEEDED);
        assertThat(committed.snapshot().finalAnswer()).isEqualTo("first");
        assertThat(committed.snapshot().finishedAt()).isEqualTo(created.plusSeconds(5));
    }

    @Test
    void retryAfterLostCommitConfirmationIsIdempotent() {
        Instant created = createRunning("final-3");
        RunResultProjector.FinalProjection projection = projector.project("final-3",
                AgentResult.success("final-3", "done", List.of(
                        step("final-3", 1, AgentStepType.TERMINATION, true)), TokenUsage.empty()),
                created.plusSeconds(5), false, true);
        persistence.complete(projection);

        RunPersistence.CommittedTerminal retry = persistence.complete(projection);

        assertThat(retry.written()).isFalse();
        assertThat(retry.conflict()).isFalse();
        assertThat(retry.snapshot().finishedAt()).isEqualTo(created.plusSeconds(5));
        assertThat(steps.findByTaskIdOrderByStepNoAsc("final-3")).hasSize(1);
    }

    private Instant createRunning(String taskId) {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        persistence.createQueued(new RunPersistence.CreateCommand(taskId, "session-" + taskId,
                "user-1", "input", "title", created));
        persistence.markRunning(taskId, created.plusSeconds(1));
        return created;
    }

    private static AgentStepRecord step(String taskId, int number, AgentStepType type,
                                        boolean terminal) {
        return new AgentStepRecord(taskId, number, type, "name", "input", "output",
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
