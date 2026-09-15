package com.agentflow.web.run;

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

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = RunStartTransactionTest.TestApplication.class)
class RunStartTransactionTest {

    @Autowired RunPersistence persistence;
    @Autowired AgentTaskRepository tasks;

    @Test
    void locksAndTransitionsQueuedRunToRunning() {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        create("start-1", created);

        RunPersistence.StartResult result =
                persistence.markRunning("start-1", created.plusSeconds(2));

        assertThat(result.outcome()).isEqualTo(RunPersistence.StartOutcome.STARTED);
        assertThat(result.snapshot().status()).isEqualTo(RunLifecycleStatus.RUNNING);
        assertThat(result.snapshot().startedAt()).isEqualTo(created.plusSeconds(2));
    }

    @Test
    void retryAfterLostConfirmationKeepsOriginalStartedAt() {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        create("start-2", created);
        persistence.markRunning("start-2", created.plusSeconds(3));

        RunPersistence.StartResult retry =
                persistence.markRunning("start-2", created.plusSeconds(30));

        assertThat(retry.outcome()).isEqualTo(RunPersistence.StartOutcome.ALREADY_RUNNING);
        assertThat(retry.snapshot().startedAt()).isEqualTo(created.plusSeconds(3));
    }

    @Test
    void terminalFactWinsOverAStaleStartAttempt() throws Exception {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        create("start-3", created);
        AgentTaskEntity task = tasks.findById("start-3").orElseThrow();
        set(task, "status", "FAILED");
        set(task, "finishedAt", created.plusSeconds(4));
        set(task, "terminationReason", "INTERNAL_ERROR");
        set(task, "errorCode", "INTERNAL_ERROR");
        tasks.saveAndFlush(task);

        RunPersistence.StartResult result =
                persistence.markRunning("start-3", created.plusSeconds(5));

        assertThat(result.outcome()).isEqualTo(RunPersistence.StartOutcome.TERMINAL);
        assertThat(result.snapshot().status()).isEqualTo(RunLifecycleStatus.FAILED);
        assertThat(result.snapshot().startedAt()).isNull();
    }

    @Test
    void invalidStartTimeRollsBackWithoutDriftingTheQueuedFact() {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        create("start-4", created);

        assertThatThrownBy(() -> persistence.markRunning("start-4", created.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        AgentTaskEntity task = tasks.findById("start-4").orElseThrow();
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getStartedAt()).isNull();
    }

    private void create(String taskId, Instant created) {
        persistence.createQueued(new RunPersistence.CreateCommand(taskId, "session-" + taskId,
                "user-1", "input", "title", created));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = AgentWebAutoConfiguration.class)
    @EntityScan(basePackageClasses = AgentTaskEntity.class)
    @EnableJpaRepositories(basePackageClasses = AgentTaskRepository.class)
    @Import(RunPersistence.class)
    @Configuration(proxyBeanMethods = false)
    static class TestApplication {
    }
}
