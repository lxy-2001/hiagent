package com.agentflow.demo;

import com.agentflow.web.agent.AgentSessionEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.Path;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class LocalStorageFileTest {
    @TempDir Path directory;
    private static com.agentflow.core.context.HistoryStore.Io io() {
        return new com.agentflow.core.context.HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE, Instant.now().plusSeconds(60));
    }
    private static final com.agentflow.core.context.HistoryStore.Scope SCOPE =
            new com.agentflow.core.context.HistoryStore.Scope("owner", "committed", "reopen-run");
    private static final com.agentflow.core.context.HistoryStore.Checkpoint CHECKPOINT =
            new com.agentflow.core.context.HistoryStore.Checkpoint("reopen-checkpoint", 2, null, 2, "saved summary", 4, 50, 10,
                    3, "request-reopen", "h01-v1", "profile", Instant.parse("2026-10-07T00:00:00Z"));
    @Test void defaultDemoAssemblesDurableResponsesWithoutExternalServices() {
        try (var app = new SpringApplicationBuilder(AgentFlowDemoApplication.class).properties("spring.main.banner-mode=off")
                .run("--server.port=0", "--agentflow.harness.storage.data-root=" + directory.toString().replace('\\','/'),
                     "--agentflow.harness.model.base-url=http://127.0.0.1:1/v1", "--agentflow.harness.model.api-key=offline-test-only",
                     "--agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32")) {
            assertThat(app.getEnvironment().getProperty("agentflow.harness.enabled", Boolean.class)).isTrue();
            assertThat(app.getBean(com.agentflow.core.model.AgentModelClient.class)).isInstanceOf(com.agentflow.llm.OpenAiResponsesAgentModelClient.class);
            assertThat(app.getBean(com.agentflow.core.context.CompactionModel.class)).isInstanceOf(com.agentflow.llm.ResponsesCompactionModel.class);
            assertThat(app.getBean(com.agentflow.web.context.ContextSessionService.class)).isInstanceOf(com.agentflow.web.context.ContextSessionService.class);
            assertThat(app.getBean(com.agentflow.web.support.WebRequestStateStore.class)).isInstanceOf(com.agentflow.web.support.LocalWebRequestStateStore.class);
            var storage = app.getBean(com.agentflow.web.context.HarnessStorageProperties.class);
            assertThat(storage.getDataRoot()).isEqualTo(directory.toAbsolutePath().normalize());
            assertThatThrownBy(() -> storage.setDataRoot(directory.toAbsolutePath().getRoot())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.setDataRoot(Path.of("."))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void migratesValidatesRollsBackLocksAndReopensFileDatabase() throws Exception {
        assertThat(new ClassPathResource("application-local-harness.yml").exists())
                .as("local-harness must supply the file datasource profile").isTrue();
        try (var app = start()) {
            var jdbc = app.getBean(JdbcTemplate.class);
            var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
            var em = app.getBean(EntityManager.class);
            tx.executeWithoutResult(s -> em.persist(new AgentSessionEntity("committed", "owner", "saved", Instant.now())));
            tx.executeWithoutResult(s -> { em.persist(new AgentSessionEntity("rolled-back", "owner", "discard", Instant.now())); s.setRollbackOnly(); });
            assertThat(jdbc.queryForObject("select count(*) from agent_session", Integer.class)).isEqualTo(1);
            tx.executeWithoutResult(s -> em.persist(new com.agentflow.web.agent.AgentTaskEntity(SCOPE.runId(), SCOPE.sessionId(),
                    SCOPE.userId(), "input", "RUNNING", Instant.now(), 1, false)));
            var history = app.getBean(com.agentflow.web.context.JpaHistoryStore.class);
            var records = java.util.List.of(
                    new com.agentflow.core.context.HistoryStore.Item(com.agentflow.core.context.HistoryStore.Kind.USER_MESSAGE,
                            com.agentflow.core.model.ModelMessage.user("saved goal"), Instant.now(), null, null, null, null, null),
                    new com.agentflow.core.context.HistoryStore.Item(com.agentflow.core.context.HistoryStore.Kind.ASSISTANT_MESSAGE,
                            com.agentflow.core.model.ModelMessage.assistant("saved answer"), Instant.now(), null, null, null, null, null),
                    new com.agentflow.core.context.HistoryStore.Item(com.agentflow.core.context.HistoryStore.Kind.USER_MESSAGE,
                            com.agentflow.core.model.ModelMessage.user("recent tail"), Instant.now(), null, null, null, null, null));
            history.appendBatch(SCOPE, "reopen-messages", history.payloadHash(records), records, io());
            history.saveCheckpoint(SCOPE, 1, CHECKPOINT, io());
            assertThat(jdbc.queryForObject("select count(*) from \"flyway_schema_history\" where \"success\"=true and \"version\" is not null", Integer.class)).isEqualTo(7);
            try (var first = app.getBean(javax.sql.DataSource.class).getConnection();
                 var second = app.getBean(javax.sql.DataSource.class).getConnection()) {
                first.setAutoCommit(false);
                first.createStatement().executeQuery("select * from agent_session where id='committed' for update");
                second.createStatement().execute("set lock_timeout 50");
                assertThatThrownBy(() -> second.createStatement().executeUpdate("update agent_session set title='blocked' where id='committed'"))
                        .isInstanceOf(java.sql.SQLException.class);
                first.rollback();
            }
        }
        try (var app = start()) {
            var jdbc = app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select title from agent_session where id='committed'", String.class)).isEqualTo("saved");
            assertThat(jdbc.queryForObject("select count(*) from agent_session where id='rolled-back'", Integer.class)).isZero();
            var history = app.getBean(com.agentflow.web.context.JpaHistoryStore.class);
            var state = history.state(SCOPE, io());
            assertThat(state.checkpoint()).isEqualTo(CHECKPOINT);
            assertThat(history.saveCheckpoint(SCOPE, 1, CHECKPOINT, io())).isEqualTo(CHECKPOINT);
            assertThat(history.readAfter(SCOPE, state.checkpoint().coveredThrough(), state.lastSequence(), 128, 4096, io()).records())
                    .extracting(com.agentflow.core.context.HistoryStore.Record::sequence).containsExactly(3L);
            assertThat(history.state(SCOPE, io()).lastSequence()).isEqualTo(3);
        }
    }
    private org.springframework.context.ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(StorageApp.class).profiles("local-harness")
                .properties("spring.main.web-application-type=none", "spring.main.banner-mode=off")
                .run("--agentflow.harness.storage.data-root=" + directory.toString().replace('\\','/'),
                     "--spring.autoconfigure.exclude=com.agentflow.web.autoconfigure.AgentWebAutoConfiguration,com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration,com.agentflow.llm.AgentLlmAutoConfiguration,com.agentflow.tool.AgentToolAutoConfiguration,com.agentflow.tool.ToolPolicyAutoConfiguration,com.agentflow.mcp.McpAutoConfiguration,com.agentflow.rag.AgentRagAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration");
    }
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses={AgentSessionEntity.class, com.agentflow.web.context.SessionContextEntity.class})
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackageClasses=com.agentflow.web.agent.AgentSessionRepository.class)
    static class StorageApp {
        @org.springframework.context.annotation.Bean
        com.agentflow.web.context.JpaHistoryStore history(com.agentflow.web.agent.AgentSessionRepository sessions, EntityManager manager) {
            return new com.agentflow.web.context.JpaHistoryStore(sessions, manager, new tools.jackson.databind.ObjectMapper());
        }
    }
}
