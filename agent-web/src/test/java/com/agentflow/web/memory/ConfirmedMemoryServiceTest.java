package com.agentflow.web.memory;

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
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = ConfirmedMemoryServiceTest.App.class)
class ConfirmedMemoryServiceTest {
    @Autowired ConfirmedMemoryService service;
    @Autowired AgentSessionRepository sessions;
    @Autowired ConfirmedMemoryRepository memories;
    @Autowired JdbcTemplate jdbc;

    @Test void explicitSlotsVersionChecksIdempotenceDeletionAndRecreationPreventAba() {
        session("versions");
        assertThat(service.get("owner", "versions")).extracting(ConfirmedMemoryService.Slot::version).containsExactly("0", "0");
        var first = service.put("owner", "versions", "preferred_language", "Java", 0);
        assertThat(first.version()).isEqualTo("1");
        assertThat(first.type()).isEqualTo("USER_PREFERENCE");
        assertThat(first.source()).isEqualTo("USER_CONFIRMED");
        assertThat(service.put("owner", "versions", "preferred_language", "Java", 1)).isEqualTo(first);
        assertThatThrownBy(() -> service.put("owner", "versions", "preferred_language", "Java", 0)).isInstanceOf(ConfirmedMemoryService.VersionConflictException.class);
        var deleted = service.delete("owner", "versions", "preferred_language", 1);
        assertThat(deleted.state()).isEqualTo("DELETED");
        assertThat(deleted.value()).isNull();
        assertThat(deleted.version()).isEqualTo("2");
        assertThat(service.delete("owner", "versions", "preferred_language", 2)).isEqualTo(deleted);
        assertThat(memories.findBySessionIdAndKey("versions", "preferred_language").orElseThrow().getValue()).isNull();
        assertThat(service.put("owner", "versions", "preferred_language", "Kotlin", 2).version()).isEqualTo("3");
        assertThat(service.delete("owner", "versions", "project_stack", 0).state()).isEqualTo("ABSENT");
        assertThat(memories.findBySessionId("versions")).hasSize(1);
    }

    @Test void sameVersionConcurrentChangesHaveExactlyOneWinner() throws Exception {
        session("race");
        service.put("owner", "race", "project_stack", "initial", 0);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> writeAfter(start, "Java"));
            var b = executor.submit(() -> writeAfter(start, "Kotlin"));
            start.countDown();
            assertThat((a.get(5, TimeUnit.SECONDS) ? 1 : 0) + (b.get(5, TimeUnit.SECONDS) ? 1 : 0)).isOne();
            assertThat(service.get("owner", "race").get(1).version()).isEqualTo("2");
        } finally { executor.shutdownNow(); }
    }

    @Test void overflowSecretsAndOwnerMismatchCannotModifyMemory() {
        session("limits");
        var first = service.put("owner", "limits", "project_stack", "Java", 0);
        jdbc.update("update agent_confirmed_memory set version=? where session_id='limits'", Long.MAX_VALUE);
        assertThat(service.put("owner", "limits", "project_stack", "Java", Long.MAX_VALUE).version()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThatThrownBy(() -> service.put("owner", "limits", "project_stack", "other", Long.MAX_VALUE))
                .isInstanceOfSatisfying(RunCoordinator.RunUnavailableException.class, e -> assertThat(e.code()).isEqualTo("MEMORY_VERSION_EXHAUSTED"));
        for (String value : java.util.List.of("token=private", " ", "x".repeat(513), "😀".repeat(257))) {
            assertThatThrownBy(() -> service.put("owner", "limits", "preferred_language", value, 0)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.get("stranger", "limits")).isInstanceOf(RunCoordinator.RunNotFoundException.class);
        assertThatThrownBy(() -> service.put("stranger", "limits", "project_stack", "other", Long.MAX_VALUE)).isInstanceOf(RunCoordinator.RunNotFoundException.class);
        assertThat(memories.findBySessionId("limits")).hasSize(1);
    }

    private boolean writeAfter(CountDownLatch start, String value) throws Exception {
        if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        try { service.put("owner", "race", "project_stack", value, 1); return true; }
        catch (ConfirmedMemoryService.VersionConflictException expected) { return false; }
    }
    private void session(String id) { sessions.saveAndFlush(new AgentSessionEntity(id, "owner", "title", Instant.now())); }
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {AgentWebAutoConfiguration.class, com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration.class})
    @EntityScan(basePackageClasses = {AgentTaskEntity.class, ConfirmedMemoryEntity.class})
    @EnableJpaRepositories(basePackageClasses = {AgentTaskRepository.class, ConfirmedMemoryRepository.class})
    @Import({ConfirmedMemoryService.class, ContextTextPolicy.class})
    static class App { }
}
