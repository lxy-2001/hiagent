package com.agentflow.demo;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunLifecycleMigrationTest {

    private static final String V1_SHA256 =
            "c80b12200c15acf5202818c39d751721e18b67f69d1a53e9a83a360987b7e861";

    @Test
    void migratesV1RowsConservativelyWithoutChangingTheV1Artifact() throws Exception {
        ClassPathResource v1 = new ClassPathResource("db/migration/V1__init_schema.sql");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(v1.getInputStream().readAllBytes()))).isEqualTo(V1_SHA256);

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(v1).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertOldTask(jdbc, "success", "SUCCEEDED", "answer");
        insertOldTask(jdbc, "failure", "FAILED", "old secret error");
        insertOldTask(jdbc, "empty", "SUCCEEDED", "");
        insertOldTask(jdbc, "running", "RUNNING", null);
        jdbc.update("insert into agent_step (id,task_id,step_no,step_type,status,latency_ms,created_at) "
                + "values ('step-1','success',1,'FINAL','SUCCESS',0,CURRENT_TIMESTAMP)");

        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V2__run_lifecycle.sql")).execute(dataSource);

        Map<String, Object> success = row(jdbc, "success");
        assertThat(success).containsEntry("status", "SUCCEEDED")
                .containsEntry("termination_reason", "COMPLETED")
                .containsEntry("recording_complete", false);
        assertThat(success.get("finished_at")).isNotNull();
        assertThat(success.get("runtime_reason")).isNull();

        for (String id : List.of("failure", "empty")) {
            Map<String, Object> legacy = row(jdbc, id);
            assertThat(legacy).containsEntry("status", "FAILED")
                    .containsEntry("termination_reason", "LEGACY_FAILURE")
                    .containsEntry("recording_complete", false);
            assertThat(legacy.get("final_answer")).isNull();
            assertThat(legacy.get("finished_at")).isNotNull();
        }

        Map<String, Object> running = row(jdbc, "running");
        assertThat(running).containsEntry("status", "RUNNING")
                .containsEntry("cancel_requested", false)
                .containsEntry("recording_complete", false);
        assertThat(running.get("termination_reason")).isNull();
        assertThat(running.get("finished_at")).isNull();

        assertThat(jdbc.queryForObject("select count(*) from agent_step where task_id='success'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select index_name from information_schema.indexes "
                        + "where table_name='agent_task'"))
                .extracting(row -> String.valueOf(row.get("index_name")).toLowerCase())
                .contains("idx_agent_task_status_id");
    }

    private static void insertOldTask(JdbcTemplate jdbc, String id, String status, String answer) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 10, 0);
        jdbc.update("insert into agent_session (id,user_id,title,created_at) values (?,?,?,?)",
                "session-" + id, "user-1", id, now);
        jdbc.update("insert into agent_task (id,session_id,user_id,user_input,status,final_answer,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,?)",
                id, "session-" + id, "user-1", "input", status, answer, now, now.plusSeconds(5));
    }

    private static Map<String, Object> row(JdbcTemplate jdbc, String id) {
        return jdbc.queryForMap("select * from agent_task where id=?", id);
    }
}
