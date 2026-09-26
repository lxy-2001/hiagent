package com.agentflow.web.run;

import java.time.Instant;
import java.util.List;
import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = RunStepPersistenceTest.TestApplication.class)
class RunTimestampPersistenceTest {
    @Autowired RunPersistence persistence;

    @Test void databasePrecisionMustNotStrandAStartOrTerminalRetry() {
        Instant observed = Instant.parse("2026-01-01T00:00:00.000000900Z");
        persistence.createQueued(new RunPersistence.CreateCommand("precision-run", "precision-session",
                "owner", "input", "title", observed));
        var start = persistence.markRunning("precision-run", observed);
        assertThat(start.snapshot().startedAt()).isAfterOrEqualTo(start.snapshot().createdAt());
        var projection = new RunResultProjector().project("precision-run",
                AgentResult.success("precision-run", "done", List.of(), TokenUsage.empty()), observed, false, true);
        var terminal = persistence.complete(projection);
        assertThat(terminal.snapshot().finishedAt()).isAfterOrEqualTo(terminal.snapshot().startedAt());
        assertThat(persistence.complete(projection).conflict()).isFalse();
    }

    @Test void genuinelyEarlierStartIsStillRejectedWithoutChangingTheQueuedFact() {
        Instant created = Instant.parse("2026-01-01T00:00:10Z");
        persistence.createQueued(new RunPersistence.CreateCommand("clock-run", "clock-session",
                "owner", "input", "title", created));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                persistence.markRunning("clock-run", created.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(persistence.getOwned("owner", "clock-run").orElseThrow().status())
                .isEqualTo(RunLifecycleStatus.QUEUED);
    }
}
