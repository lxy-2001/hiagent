package com.agentflow.demo.approval;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class ApprovalMigrationTest {
    @Test void upgradesV5AndEnforcesUniqueCallAndCascadingDeletion() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:approval-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).target("5").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into agent_task(id,session_id,user_id,user_input,status,created_at,updated_at,turn_sequence) values('old','s','u','question','QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");
        assertThat(Flyway.configure().dataSource(ds).load().migrate().targetSchemaVersion).isEqualTo("6");
        assertThat(jdbc.queryForObject("select status from agent_task where id='old'", String.class)).isEqualTo("QUEUED");
        String insert = "insert into agent_tool_invocation(id,task_id,call_id,owner_id,tool_name,definition_version,policy_version,arguments_digest,risk,policy_action,effect,created_at,dispatch_count,outcome) values(?, 'old','call','u','local',?,?,?,'LOW','ALLOW','READ_ONLY',CURRENT_TIMESTAMP,0,'NOT_DISPATCHED')";
        jdbc.update(insert, "a", "a".repeat(64), "b".repeat(64), "c".repeat(64));
        assertThatThrownBy(() -> jdbc.update(insert, "b", "a".repeat(64), "b".repeat(64), "c".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update agent_tool_invocation set dispatch_count=1 where id='a'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update agent_tool_invocation set action_summary='fake' where id='a'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("delete from agent_task where id='old'");
        assertThat(jdbc.queryForObject("select count(*) from agent_tool_invocation", Integer.class)).isZero();
    }
}
