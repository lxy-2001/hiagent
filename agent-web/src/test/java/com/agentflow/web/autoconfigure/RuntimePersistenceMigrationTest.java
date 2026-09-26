package com.agentflow.web.autoconfigure;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.*;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.web.agent.*;
import com.agentflow.web.run.RunPersistence;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = RuntimePersistenceMigrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:runtime-migration;DB_CLOSE_DELAY=0",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.data.redis.repositories.enabled=false", "agentflow.rag.enabled=false",
        "agentflow.security.jwt.secret=fixture-only-runtime-migration-secret-008"})
class RuntimePersistenceMigrationTest {
    @Autowired AgentRuntime runtime;
    @Autowired StepRecorder recorder;
    @Autowired RunPersistence persistence;
    @Autowired AgentStepRepository steps;

    @Test void realRuntimeRecordsItsActualStepsThroughJpa() {
        assertThat(recorder).isInstanceOf(JpaStepRecorder.class);
        var now = Instant.parse("2026-09-26T00:00:00Z");
        persistence.createQueued(new RunPersistence.CreateCommand("migration-run", "migration-session", "owner", "hello", "hello", now));
        persistence.markRunning("migration-run", now);
        var result = runtime.run(new AgentRequest("migration-run", "migration-session", "owner", "hello"), e -> {});
        assertThat(result.finalAnswer()).isEqualTo("migration-ok");
        var stored = steps.findByTaskIdOrderByStepNoAsc("migration-run");
        assertThat(stored).hasSize(result.steps().size()).isNotEmpty();
        assertThat(stored.get(stored.size() - 1).getStepType()).isEqualTo("TERMINATION");
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    static class App {
        @Bean AgentModelClient model() { return request -> new FinalAnswerDecision("d", "migration-ok", TokenUsage.empty()); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
    }
}

