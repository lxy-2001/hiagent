package com.agentflow.web.conversation;

import com.agentflow.web.agent.*;
import com.agentflow.web.run.*;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = ConversationCreateTransactionTest.App.class)
class ConversationCreateTransactionTest {
    @Autowired RunPersistence persistence;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTaskRepository tasks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void allocatesAndRollsBackSessionSequenceInSameTransactionAsRun() {
        persistence.createQueued(command("a1", "a", "owner", true));
        assertThat(tasks.findById("a1").orElseThrow().getTurnSequence()).isEqualTo(1);
        persistence.createQueued(command("a2", "a", "owner", false));
        assertThat(tasks.findById("a2").orElseThrow().getTurnSequence()).isEqualTo(2);
        assertThatThrownBy(() -> persistence.createQueued(command("a2", "a", "owner", false))).isInstanceOf(RuntimeException.class);
        assertThat(sessions.findById("a").orElseThrow().getLastTurnSequence()).isEqualTo(2);
        assertThat(tasks.findById("a1").orElseThrow().getUserInput()).isEqualTo("token=[redacted]");
    }

    @Test
    void wrongOwnerAndSequenceExhaustionAreDefiniteRejectionsWithoutNewRun() {
        persistence.createQueued(command("b1", "b", "owner", true));
        assertThatThrownBy(() -> persistence.createQueued(command("b2", "b", "stranger", false)))
                .isInstanceOf(RunPersistence.CreateRejectedException.class).hasMessage("NOT_FOUND");
        assertThat(tasks.existsById("b2")).isFalse();
        jdbc.update("update agent_session set last_turn_sequence=? where id='b'", Long.MAX_VALUE);
        assertThatThrownBy(() -> persistence.createQueued(command("b3", "b", "owner", false)))
                .isInstanceOf(RunPersistence.CreateRejectedException.class).hasMessage("TURN_SEQUENCE_EXHAUSTED");
        assertThat(tasks.existsById("b3")).isFalse();
        assertThat(sessions.findById("b").orElseThrow().getLastTurnSequence()).isEqualTo(Long.MAX_VALUE);
    }

    private static RunPersistence.CreateCommand command(String run, String session, String owner, boolean create) {
        return new RunPersistence.CreateCommand(run, session, owner, "token=[redacted]", "token=[redacted]", Instant.now(), create);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {AgentWebAutoConfiguration.class, com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration.class})
    @EntityScan(basePackageClasses = AgentTaskEntity.class)
    @EnableJpaRepositories(basePackageClasses = AgentTaskRepository.class)
    @Import(RunPersistence.class)
    static class App { }
}
