package com.agentflow.demo;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

class ConversationMigrationTest {
    @Test
    void assignsStableSequencesAcrossStatusesAndPreservesEmptySessionsAndOldMigrations() throws Exception {
        var v1 = new ClassPathResource("db/migration/V1__init_schema.sql");
        var v2 = new ClassPathResource("db/migration/V2__run_lifecycle.sql");
        assertHash(v1, "c80b12200c15acf5202818c39d751721e18b67f69d1a53e9a83a360987b7e861");
        assertHash(v2, "e9573e4c96e2b4551b751d37f2fd99f48c748e977690db5c7b25b82e958af4f8");
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:conversation-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(v1, v2).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into agent_session(id,user_id,title,created_at) values ('s','u','title',CURRENT_TIMESTAMP),('empty','u','empty',CURRENT_TIMESTAMP)");
        for (String id : java.util.List.of("b", "a", "c")) {
            jdbc.update("insert into agent_task(id,session_id,user_id,user_input,status,final_answer,created_at,updated_at) values (?, 's','u','q',?,?,'2026-09-01 00:00:00','2026-09-01 00:00:00')",
                    id, id.equals("b") ? "FAILED" : "SUCCEEDED", id.equals("b") ? null : "answer");
        }
        var v3 = new ClassPathResource("db/migration/V3__conversation_turns.sql");
        assertThat(v3.exists()).as("V3 conversation migration is provided").isTrue();
        new ResourceDatabasePopulator(v3).execute(ds);
        assertThat(jdbc.queryForList("select turn_sequence from agent_task order by id", Long.class)).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.queryForObject("select last_turn_sequence from agent_session where id='s'", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select last_turn_sequence from agent_session where id='empty'", Long.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("update agent_task set turn_sequence=1 where id='b'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update agent_task set turn_sequence=0 where id='b'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static void assertHash(ClassPathResource resource, String expected) throws Exception {
        try (var input = resource.getInputStream()) {
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))).isEqualTo(expected);
        }
    }
}
