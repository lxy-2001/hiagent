package com.agentflow.web.conversation;

import com.agentflow.core.context.*;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.web.agent.*;
import com.agentflow.web.memory.*;
import com.agentflow.web.run.RunPersistence;
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
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = MemorySnapshotIsolationTest.App.class)
class MemorySnapshotIsolationTest {
    @Autowired RunPersistence persistence;
    @Autowired PersistentContextSource source;
    @Autowired ConfirmedMemoryService service;

    @Test void snapshotSeesQueuedUpdatesAndRemainsUnchangedAfterLaterUpdateAndDeletion() {
        persistence.createQueued(new RunPersistence.CreateCommand("snapshot-run", "snapshot-session", "owner", "now", "title", Instant.now()));
        service.put("owner", "snapshot-session", "project_stack", "Java17", 0);
        service.put("owner", "snapshot-session", "preferred_language", "Java", 0);
        service.put("owner", "snapshot-session", "project_stack", "Java21", 1);
        persistence.markRunning("snapshot-run", Instant.now());
        var query = new ContextSource.Query("owner", "snapshot-session", "snapshot-run");
        var snapshot = source.load(query, Duration.ofSeconds(2), CancellationSignal.NONE);
        assertThat(snapshot.memories()).extracting(ConfirmedMemory::value).containsExactly("Java", "Java21");
        assertThat(snapshot.memories().get(1).version()).isEqualTo(2);
        service.delete("owner", "snapshot-session", "project_stack", 2);
        service.put("owner", "snapshot-session", "preferred_language", "Kotlin", 1);
        assertThat(snapshot.memories()).extracting(ConfirmedMemory::value).containsExactly("Java", "Java21");
        var later = source.load(query, Duration.ofSeconds(2), CancellationSignal.NONE);
        assertThat(later.memories()).extracting(ConfirmedMemory::value).containsExactly("Kotlin");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = AgentWebAutoConfiguration.class)
    @EntityScan(basePackageClasses = {AgentTaskEntity.class, ConfirmedMemoryEntity.class})
    @EnableJpaRepositories(basePackageClasses = {AgentTaskRepository.class, ConfirmedMemoryRepository.class})
    @Import({RunPersistence.class, PersistentContextSource.class, ConfirmedMemoryService.class, ContextTextPolicy.class})
    static class App { }
}
