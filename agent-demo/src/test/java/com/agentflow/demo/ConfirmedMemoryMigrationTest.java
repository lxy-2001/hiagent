package com.agentflow.demo;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class ConfirmedMemoryMigrationTest {
    @Test void createsConstrainedSlotsWithoutImportingLegacyMemory() throws Exception {
        try (var input = new ClassPathResource("db/migration/V3__conversation_turns.sql").getInputStream()) {
            assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())))
                    .isEqualTo("619ef1dcf9237e51facb5a91b19e48384b7ca14dc13ab55fbb93fd1c3773043c");
        }
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:confirmed-memory-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__init_schema.sql"),
                new ClassPathResource("db/migration/V2__run_lifecycle.sql"), new ClassPathResource("db/migration/V3__conversation_turns.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into agent_session(id,user_id,title,created_at) values('s','u','title',CURRENT_TIMESTAMP)");
        jdbc.update("insert into agent_memory(id,session_id,memory_type,content,created_at) values('old','s','legacy','unconfirmed',CURRENT_TIMESTAMP)");
        var v4 = new ClassPathResource("db/migration/V4__confirmed_memory.sql");
        assertThat(v4.exists()).isTrue();
        new ResourceDatabasePopulator(v4).execute(ds);
        assertThat(jdbc.queryForObject("select count(*) from agent_confirmed_memory", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agent_memory", Integer.class)).isOne();
        jdbc.update("insert into agent_confirmed_memory(id,session_id,memory_key,memory_value,state,version,source,updated_at) values('m','s','project_stack','Java','ACTIVE',1,'USER_CONFIRMED',CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> jdbc.update("insert into agent_confirmed_memory(id,session_id,memory_key,memory_value,state,version,source,updated_at) values('duplicate','s','project_stack','Java','ACTIVE',1,'USER_CONFIRMED',CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update agent_confirmed_memory set memory_key='arbitrary' where id='m'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update agent_confirmed_memory set state='DELETED',memory_value=null,source=null,version=2 where id='m'");
        assertThat(jdbc.queryForMap("select * from agent_confirmed_memory where id='m'")).containsEntry("memory_value", null).containsEntry("version", 2L);
    }
}
