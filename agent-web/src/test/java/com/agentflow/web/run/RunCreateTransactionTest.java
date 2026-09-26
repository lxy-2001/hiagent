package com.agentflow.web.run;

import com.agentflow.web.agent.AgentSessionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = RunCreateTransactionTest.TestApplication.class)
class RunCreateTransactionTest {

    @Autowired RunPersistence persistence;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTaskRepository tasks;

    @Test
    void createsSessionAndPreallocatedQueuedRunInOneCommittedTransaction() {
        Instant now = Instant.parse("2026-09-15T10:00:00Z");

        RunSnapshot snapshot = persistence.createQueued(command("run-1", "session-1", now));

        assertThat(snapshot.taskId()).isEqualTo("run-1");
        assertThat(snapshot.sessionId()).isEqualTo("session-1");
        assertThat(snapshot.status()).isEqualTo(RunLifecycleStatus.QUEUED);
        assertThat(snapshot.createdAt()).isEqualTo(now);
        assertThat(tasks.findById("run-1")).get()
                .extracting(AgentTaskEntity::getStatus).isEqualTo("QUEUED");
        assertThat(sessions.existsById("session-1")).isTrue();
    }

    @Test
    void rollsBackSessionWhenTaskInsertFails() {
        Instant now = Instant.parse("2026-09-15T10:00:00Z");
        long tasksBefore = tasks.count();
        persistence.createQueued(command("same-run", "session-first", now));

        assertThatThrownBy(() -> persistence.createQueued(
                command("same-run", "orphan-session", now.plusSeconds(1))))
                .isInstanceOf(RuntimeException.class);

        assertThat(sessions.existsById("orphan-session")).isFalse();
        assertThat(tasks.count()).isEqualTo(tasksBefore + 1);
    }

    @Test
    void persistenceBoundaryHasNoEventOrExecutorDependency() {
        assertThat(RunPersistence.class.getDeclaredFields())
                .allMatch(field -> !java.util.concurrent.Executor.class.isAssignableFrom(field.getType())
                        && !RunEventHub.class.isAssignableFrom(field.getType()));
    }

    private static RunPersistence.CreateCommand command(String taskId, String sessionId, Instant now) {
        return new RunPersistence.CreateCommand(taskId, sessionId, "user-1", "hello",
                "hello", now);
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
