package com.agentflow.web.conversation;

import com.agentflow.core.context.*;
import com.agentflow.core.cancel.CancellationSignal;
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
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = PersistentContextSourceTest.App.class)
class PersistentContextSourceTest {
    @Autowired RunPersistence persistence;
    @Autowired PersistentContextSource source;
    @Autowired JdbcTemplate jdbc;

    @Test
    void usesOnlyLatestTwentySuccessFactsAndIncludesIncompleteRecording() {
        for (int i = 1; i <= 25; i++) turn("history", i, i == 24 ? "FAILED" : "SUCCEEDED", "q" + i, "a" + i);
        current("history", 26);
        var seed = source.load(new ContextSource.Query("owner", "history", "history-26"), Duration.ofSeconds(2), CancellationSignal.NONE);
        assertThat(seed.historyThroughTurn()).isEqualTo(25);
        assertThat(seed.candidateLimited()).isTrue();
        assertThat(seed.turns()).hasSize(20);
        assertThat(seed.turns()).extracting(ConversationTurn::turnSequence).isSorted().doesNotContain(24L, 26L);
        assertThat(seed.turns().get(0).turnSequence()).isEqualTo(5);
        assertThat(seed.turns().get(19).assistantText()).isEqualTo("a25");
        assertThat(seed.sourceDiscardedCount()).isZero();
    }

    @Test
    void skipsOversizedLegacyRowsBeforeAndAfterUtf16AndRedactionChecks() {
        turn("bad", 1, "SUCCEEDED", "x".repeat(8001), "answer");
        turn("bad", 2, "SUCCEEDED", "😀".repeat(4001), "answer");
        turn("bad", 3, "SUCCEEDED", "x".repeat(7993) + "token=x", "answer");
        turn("bad", 4, "SUCCEEDED", "safe token=private-value", "answer");
        turn("bad", 5, "SUCCEEDED", "question", "x".repeat(65537));
        current("bad", 6);
        var seed = source.load(new ContextSource.Query("owner", "bad", "bad-6"), Duration.ofSeconds(2), CancellationSignal.NONE);
        assertThat(seed.turns()).hasSize(1);
        assertThat(seed.turns().get(0).userText()).isEqualTo("safe token=[redacted]");
        assertThat(seed.sourceDiscardedCount()).isEqualTo(4);
    }

    @Test
    void emptyHistoryIsValidButWrongOwnerMissingCurrentAndCancellationAreFailures() {
        current("empty", 1);
        assertThat(source.load(new ContextSource.Query("owner", "empty", "empty-1"), Duration.ofSeconds(2), CancellationSignal.NONE).turns()).isEmpty();
        for (var query : java.util.List.of(new ContextSource.Query("stranger", "empty", "empty-1"), new ContextSource.Query("owner", "empty", "missing"))) {
            assertThatThrownBy(() -> source.load(query, Duration.ofSeconds(2), CancellationSignal.NONE)).isInstanceOf(ContextSource.ContextSourceException.class);
        }
        assertThatThrownBy(() -> source.load(new ContextSource.Query("owner", "empty", "empty-1"), Duration.ofSeconds(2), () -> true))
                .isInstanceOf(ContextSource.ContextSourceException.class);
    }

    @Test
    void realCoordinatorAndRuntimeContinueAfterFailureUsingOnlySuccessfulFacts() {
        var requests = new java.util.ArrayList<com.agentflow.core.model.AgentModelRequest>();
        com.agentflow.core.model.AgentModelClient model = request -> {
            requests.add(request);
            if (request.input().equals("fail")) throw new IllegalStateException("controlled model failure");
            return new com.agentflow.core.model.FinalAnswerDecision("d-" + requests.size(), "answer-" + requests.size(), com.agentflow.core.chat.TokenUsage.empty());
        };
        var registry = org.mockito.Mockito.mock(com.agentflow.core.tool.ToolRegistry.class);
        org.mockito.Mockito.when(registry.enabledDefinitions()).thenReturn(java.util.List.of());
        var runtime = new com.agentflow.core.runtime.DefaultAgentRuntime(model, registry, null);
        var executor = org.mockito.Mockito.mock(BoundedRunExecutor.class);
        org.mockito.Mockito.when(executor.dispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    invocation.<Runnable>getArgument(2).run();
                    return new BoundedRunExecutor.Dispatch(false, null);
                });
        var hub = new InMemoryRunEventHub(new tools.jackson.databind.ObjectMapper(), 256, 1048576, 16384);
        var ids = new java.util.concurrent.atomic.AtomicInteger();
        String session;
        try (var coordinator = new RunCoordinator(source, runtime, persistence, hub, new RunEventProjector(), new RunResultProjector(),
                executor, RunLifecycleProperties.defaults(), java.time.Clock.systemUTC(), System::nanoTime, () -> "continuity-" + ids.incrementAndGet())) {
            var first = coordinator.create("owner", "token=private-value question");
            assertThat(coordinator.getOwned("owner", first.taskId()).status()).isEqualTo(RunLifecycleStatus.SUCCEEDED);
            assertThat(coordinator.inFlightCount()).isZero();
            var failed = coordinator.create("owner", "fail", first.sessionId());
            assertThat(coordinator.getOwned("owner", failed.taskId()).status()).isEqualTo(RunLifecycleStatus.FAILED);
            session = first.sessionId();
            assertThat(coordinator.getOwned("owner", first.taskId()).input()).isEqualTo(requests.get(0).input());
        }
        // Rebuild process-owned runtime, coordinator and event state; only database facts survive.
        var restartedRuntime = new com.agentflow.core.runtime.DefaultAgentRuntime(model, registry, null);
        var restartedHub = new InMemoryRunEventHub(new tools.jackson.databind.ObjectMapper(), 256, 1048576, 16384);
        try (var coordinator = new RunCoordinator(source, restartedRuntime, persistence, restartedHub,
                new RunEventProjector(), new RunResultProjector(), executor, RunLifecycleProperties.defaults(),
                java.time.Clock.systemUTC(), System::nanoTime, () -> "continuity-" + ids.incrementAndGet())) {
            assertThat(coordinator.recoverInterrupted()).isTrue();
            var last = coordinator.create("owner", "continue", session);
            assertThat(coordinator.getOwned("owner", last.taskId()).status()).isEqualTo(RunLifecycleStatus.SUCCEEDED);
            assertThat(requests).hasSize(3);
            assertThat(requests.get(2).messages()).extracting(com.agentflow.core.model.ModelMessage::content)
                    .contains("token=[redacted] question", "answer-1", "continue").doesNotContain("fail", "private-value");
        }
    }

    @Test
    void databaseFailureIsNeverReportedAsAnEmptySeed() {
        var manager = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        org.mockito.Mockito.when(manager.createQuery(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(jakarta.persistence.Tuple.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> new PersistentContextSource(manager, new ContextTextPolicy()).load(
                new ContextSource.Query("owner", "session", "run"), Duration.ofSeconds(2), CancellationSignal.NONE))
                .isInstanceOf(ContextSource.ContextSourceException.class).hasMessage("CONTEXT_SOURCE_UNAVAILABLE");
    }

    private void current(String session, int sequence) {
        persistence.createQueued(new RunPersistence.CreateCommand(session + "-" + sequence, session, "owner", "now", "title", Instant.now(), sequence == 1));
        persistence.markRunning(session + "-" + sequence, Instant.now());
    }
    private void turn(String session, int sequence, String status, String input, String answer) {
        persistence.createQueued(new RunPersistence.CreateCommand(session + "-" + sequence, session, "owner", "initial", "title", Instant.now(), sequence == 1));
        jdbc.update("update agent_task set status=?,user_input=?,final_answer=? where id=?", status, input, answer, session + "-" + sequence);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = AgentWebAutoConfiguration.class)
    @EntityScan(basePackageClasses = {AgentTaskEntity.class, com.agentflow.web.memory.ConfirmedMemoryEntity.class})
    @EnableJpaRepositories(basePackageClasses = AgentTaskRepository.class)
    @Import({RunPersistence.class, PersistentContextSource.class, ContextTextPolicy.class})
    static class App { }
}
