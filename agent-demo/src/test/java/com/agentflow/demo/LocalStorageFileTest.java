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
            assertThat(jdbc.queryForObject("select count(*) from \"flyway_schema_history\" where \"success\"=true and \"version\" is not null", Integer.class)).isEqualTo(6);
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
        }
    }
    private org.springframework.context.ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(StorageApp.class).profiles("local-harness")
                .properties("spring.main.web-application-type=none", "spring.main.banner-mode=off")
                .run("--agentflow.harness.storage.data-root=" + directory.toString().replace('\\','/'),
                     "--spring.autoconfigure.exclude=com.agentflow.web.autoconfigure.AgentWebAutoConfiguration,com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration,com.agentflow.llm.autoconfigure.AgentLlmAutoConfiguration,com.agentflow.tool.AgentToolAutoConfiguration,com.agentflow.tool.ToolPolicyAutoConfiguration,com.agentflow.mcp.McpAutoConfiguration,com.agentflow.rag.autoconfigure.AgentRagAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration");
    }
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses=AgentSessionEntity.class)
    static class StorageApp { }
}
