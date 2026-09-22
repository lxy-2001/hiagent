package com.agentflow.demo;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class RunCitationMigrationTest {
    @Test
    void upgradesV4WithoutChangingExistingRunsOrKnowledge() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:citations-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).target("4").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into agent_task(id,session_id,user_id,user_input,status,created_at,updated_at,turn_sequence) values('old','s','u','question','QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");
        var migrated = Flyway.configure().dataSource(ds).load().migrate();
        assertThat(migrated.targetSchemaVersion).isEqualTo("5");
        assertThat(jdbc.queryForMap("select require_evidence,citations_json from agent_task where id='old'"))
                .containsEntry("require_evidence", false).containsEntry("citations_json", null);
        jdbc.update("update agent_task set citations_json=? where id='old'", "x".repeat(65536));
        assertThat(jdbc.queryForObject("select octet_length(citations_json) from agent_task where id='old'", Long.class)).isEqualTo(65536L);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_document", Long.class)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from knowledge_chunk", Long.class)).isNotNull();
    }
}
