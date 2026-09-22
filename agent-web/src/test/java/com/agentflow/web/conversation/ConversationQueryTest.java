package com.agentflow.web.conversation;

import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.web.agent.*;
import com.agentflow.web.run.RunCoordinator;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = ConversationQueryTest.App.class)
class ConversationQueryTest {
    @Autowired ConversationService service;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTaskRepository tasks;
    @Autowired JdbcTemplate jdbc;

    @Test void listsOnlyOwnerWithStableTimestampTieBreakAndEnforcesDetailOwnership() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        sessions.saveAndFlush(new AgentSessionEntity("list-a", "listing", "token=private", now));
        sessions.saveAndFlush(new AgentSessionEntity("list-b", "listing", "second", now));
        sessions.saveAndFlush(new AgentSessionEntity("list-c", "stranger", "hidden", now));
        var first = service.list("listing", null, 1);
        assertThat(first.items()).extracting(ConversationService.Conversation::sessionId).containsExactly("list-b");
        assertThat(first.hasMore()).isTrue();
        var second = service.list("listing", first.nextBefore(), 1);
        assertThat(second.items()).extracting(ConversationService.Conversation::sessionId).containsExactly("list-a");
        assertThat(second.items().get(0).title()).doesNotContain("private");
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextBefore()).isNull();
        assertThat(service.get("listing", "list-b").lastTurnSequence()).isEqualTo("0");
        assertThatThrownBy(() -> service.get("stranger", "list-b")).isInstanceOf(RunCoordinator.RunNotFoundException.class);
    }

    @Test void returnsTerminalFactsAndMarksOversizedContentUnavailableWithoutTruncating() {
        sessions.saveAndFlush(new AgentSessionEntity("query", "owner", "title", Instant.now()));
        turn(1, "SUCCEEDED", "token=secret", "answer");
        turn(2, "FAILED", "failed input", "private failure detail");
        turn(3, "SUCCEEDED", "x".repeat(8001), "answer");
        turn(4, "SUCCEEDED", "😀".repeat(4001), "answer");
        turn(5, "SUCCEEDED", "x".repeat(7993) + "token=x", "answer");
        turn(6, "SUCCEEDED", "question", "x".repeat(65537));
        turn(7, "RUNNING", "in flight", null);
        var page = service.turns("owner", "query", "0", null, 20);
        assertThat(page.items()).hasSize(6);
        assertThat(page.untilSequence()).isEqualTo("6");
        assertThat(page.items().get(0).input()).doesNotContain("secret");
        assertThat(page.items().get(0).finalAnswer()).isEqualTo("answer");
        assertThat(page.items().get(1).input()).isEqualTo("failed input");
        assertThat(page.items().get(1).finalAnswer()).isNull();
        for (var turn : page.items().subList(2, 6)) {
            assertThat(turn.contentUnavailable()).isTrue();
            assertThat(turn.input()).isNull();
            assertThat(turn.finalAnswer()).isNull();
        }
        assertThatThrownBy(() -> service.turns("stranger", "query", "0", null, 20)).isInstanceOf(RunCoordinator.RunNotFoundException.class);
    }

    private void turn(long sequence, String status, String input, String answer) {
        String id = "query-" + sequence;
        tasks.saveAndFlush(new AgentTaskEntity(id, "query", "owner", input, status, Instant.now(), sequence));
        jdbc.update("update agent_task set final_answer=? where id=?", answer, id);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = AgentWebAutoConfiguration.class)
    @EntityScan(basePackageClasses = AgentTaskEntity.class)
    @EnableJpaRepositories(basePackageClasses = AgentTaskRepository.class)
    @Import({ConversationService.class, ContextTextPolicy.class})
    static class App {
        @Bean RunCoordinator coordinator() { return mock(RunCoordinator.class); }
    }
}
