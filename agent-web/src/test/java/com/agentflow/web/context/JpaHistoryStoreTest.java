package com.agentflow.web.context;

import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.web.agent.*;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = JpaHistoryStoreTest.Application.class,
        properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false"})
class JpaHistoryStoreTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("history").toString().replace('\\','/') + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE");
    }
    @Autowired HistoryStore history;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EntityManager manager;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTaskRepository tasks;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private static final Instant NOW = Instant.parse("2026-10-07T01:00:00Z");
    private static HistoryStore.Io io() { return new HistoryStore.Io(CancellationSignal.NONE, Instant.now().plusSeconds(30)); }

    @Test void confirmsWholeBatchAndRetryWithoutAllocatingAgain() {
        var scope = create("batch");
        var call = new ToolCall("c1", "read-file", new ToolArguments(Map.of("path", "hello.txt")));
        var items = List.of(item(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("Checking file")),
                item(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(call)));
        var batch = history.appendBatch(scope, "decision-1", "same-hash", items, io());
        assertThat(batch.firstSequence()).isEqualTo(1);
        assertThat(batch.lastSequence()).isEqualTo(2);
        assertThat(history.appendBatch(scope, "decision-1", "same-hash", items, io())).isEqualTo(batch);
        assertThat(history.state(scope, io()).lastSequence()).isEqualTo(2);
        var page = history.readAfter(scope, 0, 2, 128, 4096, io());
        assertThat(page.records()).extracting(HistoryStore.Record::sequence).containsExactly(1L, 2L);
        assertThat(page.records().get(1).item().message().toolCall().arguments().values()).containsEntry("path", "hello.txt");
        assertThatThrownBy(() -> history.appendBatch(scope, "decision-1", "changed-hash", items, io()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HISTORY_MUTATION_CONFLICT");
        assertThat(history.state(scope, io()).lastSequence()).isEqualTo(2);
    }

    @Test void checksOwnerAndRunAndLoadsOnlyBodyBudget() {
        var scope = create("page");
        history.appendBatch(scope, "batch", "hash", List.of(item(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("short")),
                item(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("large ".repeat(500)))), io());
        var first = history.readAfter(scope, 0, 2, 128, 512, io());
        assertThat(first.records()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextSequence()).isEqualTo(1);
        assertThatThrownBy(() -> history.readAfter(scope, 1, 2, 128, 512, io()))
                .hasMessageContaining("HISTORY_PAGE_TOO_LARGE");
        assertThatThrownBy(() -> history.state(new HistoryStore.Scope("other", scope.sessionId(), scope.runId()), io()))
                .hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> history.appendBatch(new HistoryStore.Scope(scope.userId(), scope.sessionId(), "missing-run"), "bad", "hash", List.of(item(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("bad"))), io()))
                .hasMessageContaining("NOT_FOUND");
    }


    @Test void atomicallySwitchesCheckpointAndConfirmsIdenticalRetry() {
        var scope = create("checkpoint");
        var call = new ToolCall("c-cp", "read-file", new ToolArguments(Map.of("path", "hello.txt")));
        history.appendBatch(scope, "prefix", "hash", List.of(
                item(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("goal")),
                item(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(call)),
                item(HistoryStore.Kind.TOOL_RESULT, new ModelMessage("tool", "file contents", "read-file", "c-cp", null)),
                item(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("continuing"))), io());
        var before = history.state(scope, io());
        var candidate = checkpoint("checkpoint-one", before.revision() + 1, null, 3, "goal and file summary");
        assertThat(history.saveCheckpoint(scope, before.revision(), candidate, io())).isEqualTo(candidate);
        assertThat(history.saveCheckpoint(scope, before.revision(), candidate, io())).isEqualTo(candidate);
        var committed = history.state(scope, io());
        assertThat(committed.revision()).isEqualTo(candidate.revision());
        assertThat(committed.checkpoint()).isEqualTo(candidate);
        assertThat(committed.lastSequence()).isEqualTo(4);
        assertThat(history.readAfter(scope, candidate.coveredThrough(), committed.lastSequence(), 128, 4096, io()).records())
                .extracting(HistoryStore.Record::sequence).containsExactly(4L);
        assertThat(history.readAfter(scope, 0, committed.lastSequence(), 128, 4096, io()).records()).hasSize(4);
        assertThatThrownBy(() -> history.saveCheckpoint(scope, before.revision(),
                checkpoint("checkpoint-one", candidate.revision(), null, 3, "different summary"), io()))
                .hasMessageContaining("CHECKPOINT_MUTATION_CONFLICT");
        assertThat(history.state(scope, io()).checkpoint()).isEqualTo(candidate);
    }

    @Test void rejectsStaleAndSplitCoverageAndRollsBackFailedSwitch() {
        var scope = create("checkpoint-failure");
        var call = new ToolCall("c-fail", "read-file", new ToolArguments(Map.of("path", "hello.txt")));
        history.appendBatch(scope, "prefix", "hash", List.of(
                item(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("goal")),
                item(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(call)),
                item(HistoryStore.Kind.TOOL_RESULT, new ModelMessage("tool", "result", "read-file", "c-fail", null)),
                item(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("tail"))), io());
        var revision = history.state(scope, io()).revision();
        assertThatThrownBy(() -> history.saveCheckpoint(scope, revision,
                checkpoint("split-checkpoint", revision + 1, null, 2, "split"), io()))
                .hasMessageContaining("CHECKPOINT_COVERAGE_INVALID");
        assertThatThrownBy(() -> history.saveCheckpoint(scope, revision - 1,
                checkpoint("stale-checkpoint", revision, null, 3, "stale"), io()))
                .hasMessageContaining("CHECKPOINT_REVISION_CONFLICT");
        var first = checkpoint("confirmed-checkpoint", revision + 1, null, 1, "old summary");
        history.saveCheckpoint(scope, revision, first, io());
        var replacement = checkpoint("failed-switch", first.revision() + 1, first.id(), 3, "new summary");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            history.saveCheckpoint(scope, first.revision(), replacement, io());
            throw new IllegalStateException("simulated failed commit");
        })).hasMessageContaining("simulated failed commit");
        assertThat(history.state(scope, io()).checkpoint()).isEqualTo(first);
        // An independent persistence context sees only the confirmed pointer and one copy of the tail.
        assertThat(history.readAfter(scope, first.coveredThrough(), 4, 128, 4096, io()).records())
                .extracting(HistoryStore.Record::sequence).containsExactly(2L, 3L, 4L);
    }

    @Test void persistsUnknownForOnlyOldUnconfirmedCallsAndNeverDuplicatesRecovery() {
        var old = create("orphan");
        var call = new ToolCall("old-call", "write-file", new ToolArguments(Map.of("content", "long ".repeat(200))));
        var completed = new ToolCall("completed-call", "read-file", new ToolArguments(Map.of("path", "hello.txt")));
        history.appendBatch(old, "old-decision", "hash", List.of(item(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(call)),
                new HistoryStore.Item(HistoryStore.Kind.TOOL_EXECUTION_INTENT, null, NOW, null, call.callId(), "INTENT", null, null),
                item(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(completed)),
                item(HistoryStore.Kind.TOOL_RESULT, new ModelMessage("tool", "read completed", completed.name(), completed.callId(), null))), io());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tasks.save(new AgentTaskEntity(old.runId(), old.sessionId(), old.userId(), "input", "TIMED_OUT", NOW, 1, false));
            tasks.save(new AgentTaskEntity("new-run-orphan", old.sessionId(), old.userId(), "follow-up", "RUNNING", NOW, 2, false));
        });
        var current = new HistoryStore.Scope(old.userId(), old.sessionId(), "new-run-orphan");
        var active = new ToolCall("active-call", "read-file", new ToolArguments(Map.of("path", "other.txt")));
        history.appendBatch(current, "current-call", "hash-current", List.of(item(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(active))), io());
        long beforeRecovery = history.state(current, io()).lastSequence();
        var recovery = new OrphanCallRecovery((JpaHistoryStore) history, new BoundedPersistenceRetry(java.time.Clock.systemUTC()),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        assertThat(recovery.recover(current, io())).isEqualTo(1);
        assertThat(recovery.recover(current, io())).isZero();
        var records = history.readAfter(old, beforeRecovery, history.state(old, io()).lastSequence(), 128, 4096, io()).records();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).runId()).isEqualTo(old.runId());
        assertThat(records.get(0).item().kind()).isEqualTo(HistoryStore.Kind.RECOVERY_NOTICE);
        assertThat(records.get(0).item().status()).isEqualTo("UNKNOWN");
        assertThat(records.get(0).item().message().role()).isEqualTo("tool");
        assertThat(records.get(0).item().message().toolCallId()).isEqualTo("old-call");
        assertThat(records.get(0).item().message().content()).contains("may have executed", "do not automatically replay");
    }

    @Test void confirmsOpaqueContinuationOwnershipAndPrunesOnlyCoveredState() {
        var scope = create("continuation");
        var mapper = new ObjectMapper();
        var store = new JpaResponsesContinuationStore((JpaHistoryStore) history, manager, mapper);
        var providerScope = new com.agentflow.llm.ResponsesContinuationStore.Scope(scope.userId(), scope.sessionId(), scope.runId(), "openai", "test-model");
        var opaque = mapper.readTree("[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque-only\"}]");
        String reference = new TransactionTemplate(transactions).execute(status -> store.stage(providerScope, "provider-request", opaque, 8L, io()));
        String confirmedReference = new TransactionTemplate(transactions).execute(status -> store.stage(providerScope, "provider-request", opaque, 8L, io()));
        assertThat(confirmedReference).isEqualTo(reference);
        java.util.Optional<tools.jackson.databind.JsonNode> initialRead = new TransactionTemplate(transactions).execute(status -> store.read(providerScope, reference, io()));
        assertThat(initialRead).contains(opaque);
        TokenEstimator visible = new TokenEstimator() { public long estimateInput(List<ModelMessage> m, List<com.agentflow.core.tool.ToolDefinition> d) { return 10; } public String version() { return "fixture"; } };
        var accounting = new ContinuationTokenEstimator(visible, jdbc, "openai", "test-model");
        var linkedMessages = List.of(ModelMessage.assistant("a").withAdapterStateRef(reference), ModelMessage.assistant("b").withAdapterStateRef(reference));
        assertThat(accounting.estimateInput(linkedMessages, List.of())).isEqualTo(18);
        assertThat(new ContinuationTokenEstimator(visible, jdbc, "openai", "other-model").estimateInput(linkedMessages, List.of())).isEqualTo(10);
        jdbc.update("update agent_responses_continuation set reasoning_token_hint=null where id=?", reference);
        long payloadBytes = jdbc.queryForObject("select payload_bytes from agent_responses_continuation where id=?", Long.class, reference);
        assertThat(accounting.estimateInput(linkedMessages, List.of())).isEqualTo(10 + payloadBytes);
        var wrongOwner = new com.agentflow.llm.ResponsesContinuationStore.Scope("someone-else", scope.sessionId(), scope.runId(), "openai", "test-model");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> store.read(wrongOwner, reference, io())))
                .hasMessageContaining("NOT_FOUND");
        var changedModel = new com.agentflow.llm.ResponsesContinuationStore.Scope(scope.userId(), scope.sessionId(), scope.runId(), "openai", "other-model");
        java.util.Optional<tools.jackson.databind.JsonNode> modelRead = new TransactionTemplate(transactions).execute(status -> store.read(changedModel, reference, io()));
        assertThat(modelRead).isEmpty();
        var linked = List.of(new HistoryStore.Item(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("visible commentary"),
                NOW, "decision-ref", null, null, null, reference));
        history.appendBatch(scope, "visible-decision", ((JpaHistoryStore) history).payloadHash(linked), linked, io());
        assertThat(history.readAfter(scope, 0, 1, 128, 4096, io()).records().get(0).item().message().content())
                .isEqualTo("visible commentary").doesNotContain("opaque-only");
        new TransactionTemplate(transactions).executeWithoutResult(status -> store.prune(providerScope, io()));
        java.util.Optional<tools.jackson.databind.JsonNode> protectedRead = new TransactionTemplate(transactions).execute(status -> store.read(providerScope, reference, io()));
        assertThat(protectedRead).contains(opaque);
        var invalid = List.of(new HistoryStore.Item(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("invalid ref"),
                NOW, "invalid", null, null, null, "missing-reference"));
        assertThatThrownBy(() -> history.appendBatch(scope, "invalid-ref", ((JpaHistoryStore) history).payloadHash(invalid), invalid, io()))
                .hasMessageContaining("CONTINUATION_REFERENCE_INVALID");
        history.saveCheckpoint(scope, 1, checkpoint("opaque-checkpoint", 2, null, 1, "visible summary"), io());
        new TransactionTemplate(transactions).executeWithoutResult(status -> store.prune(providerScope, io()));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> store.read(providerScope, reference, io())))
                .hasMessageContaining("CONTINUATION_UNAVAILABLE");
        assertThatThrownBy(() -> accounting.estimateInput(linkedMessages, List.of())).hasMessageContaining("CONTINUATION_UNAVAILABLE");
    }
    private static HistoryStore.Checkpoint checkpoint(String id, long revision, String base, long covered, String summary) {
        return new HistoryStore.Checkpoint(id, revision, base, covered, summary, 4, 100, 20,
                covered + 1, "request-" + id, "h01-v1", "profile-hash", NOW);
    }

    private HistoryStore.Scope create(String suffix) {
        String session = "session-" + suffix;
        String run = "run-" + suffix;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            sessions.save(new AgentSessionEntity(session, "owner", "title", NOW));
            tasks.save(new AgentTaskEntity(run, session, "owner", "input", "RUNNING", NOW, 1, false));
        });
        return new HistoryStore.Scope("owner", session, run);
    }
    private static HistoryStore.Item item(HistoryStore.Kind kind, ModelMessage message) {
        return new HistoryStore.Item(kind, message, NOW, "d1", message.toolCallId(), null, null, null);
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {AgentWebAutoConfiguration.class, com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration.class})
    @EntityScan(basePackageClasses = {AgentSessionEntity.class, SessionContextEntity.class})
    @EnableJpaRepositories(basePackageClasses = AgentSessionRepository.class)
    static class Application {
        @Bean HistoryStore historyStore(AgentSessionRepository sessions, EntityManager manager) {
            return new JpaHistoryStore(sessions, manager, new ObjectMapper());
        }
    }
}
