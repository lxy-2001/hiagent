package com.agentflow.web.conversation;

import com.agentflow.web.agent.AgentSessionEntity;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentTaskEntity;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.run.RunCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = ConversationQueryTest.App.class)
class ConversationPaginationTest {
    @Autowired ConversationService service;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTaskRepository tasks;
    @Autowired RunCoordinator coordinator;
    @AfterEach void resetCoordinator() { reset(coordinator); }

    @Test void fixedWindowExcludesLaterAppendsAndHandlesEmptyPageAndLargeSequences() {
        String id = "paging";
        sessions.saveAndFlush(new AgentSessionEntity(id, "owner", "title", Instant.now()));
        assertThat(service.turns("owner", id, "0", null, 20).untilSequence()).isEqualTo("0");
        turn(id, 1, "FAILED"); turn(id, 2, "FAILED");
        var first = service.turns("owner", id, "0", null, 1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.untilSequence()).isEqualTo("2");
        turn(id, 3, "FAILED");
        var second = service.turns("owner", id, first.nextAfterSequence(), first.untilSequence(), 1);
        assertThat(second.items()).extracting(ConversationService.Turn::turnSequence).containsExactly("2");
        assertThat(second.hasMore()).isFalse();
        var empty = service.turns("owner", id, "2", "2", 20);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextAfterSequence()).isEqualTo("2");
        turn(id, 9007199254740993L, "FAILED");
        assertThat(service.turns("owner", id, "3", "9007199254740993", 20).items().get(0).turnSequence()).isEqualTo("9007199254740993");
    }

    @Test void rejectsMalformedCursorsLimitsAndSequenceBounds() {
        sessions.saveAndFlush(new AgentSessionEntity("invalid-page", "owner", "title", Instant.now()));
        for (String cursor : java.util.List.of("", "!", "a".repeat(513), "e30", "eyJpZCI6ImEiLCJjcmVhdGVkQXQiOiJub3QtYS1kYXRlIn0")) {
            assertThatThrownBy(() -> service.list("owner", cursor, 20)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int limit : new int[]{0, -1, 51}) assertThatThrownBy(() -> service.list("owner", null, limit)).isInstanceOf(IllegalArgumentException.class);
        for (String after : java.util.List.of("01", "-1", "1.0", "9223372036854775808", "1")) {
            assertThatThrownBy(() -> service.turns("owner", "invalid-page", after, null, 20)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.turns("owner", "invalid-page", "0", "1", 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void startupRecoveryBlocksUnstableHolesButOrdinaryDegradedStateAllowsCommittedReads() {
        sessions.saveAndFlush(new AgentSessionEntity("holes", "owner", "title", Instant.now()));
        turn("holes", 1, "RUNNING"); turn("holes", 2, "FAILED");
        when(coordinator.isStartupRecoveryBlocked()).thenReturn(true);
        when(coordinator.availability()).thenReturn(RunCoordinator.Availability.DEGRADED);
        assertThatThrownBy(() -> service.turns("owner", "holes", "0", null, 20))
                .isInstanceOfSatisfying(RunCoordinator.RunUnavailableException.class, e -> assertThat(e.code()).isEqualTo("SERVICE_RECOVERING"));
        var first = tasks.findById("holes-1").orElseThrow();
        first.fail("recovered"); tasks.saveAndFlush(first);
        when(coordinator.isStartupRecoveryBlocked()).thenReturn(false);
        assertThat(service.turns("owner", "holes", "0", null, 20).items())
                .extracting(ConversationService.Turn::turnSequence).containsExactly("1", "2");
    }

    private void turn(String session, long sequence, String status) {
        tasks.saveAndFlush(new AgentTaskEntity(session + "-" + sequence, session, "owner", "question", status, Instant.now(), sequence));
    }
}
