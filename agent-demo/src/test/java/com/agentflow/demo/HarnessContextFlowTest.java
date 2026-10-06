package com.agentflow.demo;

import com.agentflow.web.agent.AgentSessionEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.Path;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class HarnessContextFlowTest {
    @TempDir Path directory;
    private static com.agentflow.core.context.HistoryStore.Io io() {
        return new com.agentflow.core.context.HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE, Instant.now().plusSeconds(60));
    }
    @Test void offlineHarnessRunsTwiceCompactsTwiceAndReopensWithoutToolReplay() throws Exception {
        OfflineHarnessFixture.executions.set(0);
        String session; String lastRun; String artifact; long lastSequence; long coverage;
        try (var app = startHarness()) {
            assertThat(app.getBean(com.agentflow.core.AgentRuntime.class))
                    .isInstanceOf(com.agentflow.core.runtime.DefaultAgentRuntime.class);
            assertThat(app.getBean(com.agentflow.core.tool.ToolRegistry.class).enabledToolNames())
                    .contains("offline-large-output", "read-artifact");
            var coordinator = app.getBean(com.agentflow.web.run.RunCoordinator.class);
            var first = coordinator.create("owner", "Offline fixture: preserve the goal and inspect a large result");
            session = first.sessionId();
            var firstTerminal = awaitTerminal(coordinator, first.taskId());
            assertThat(firstTerminal.status()).as("first terminal: %s", firstTerminal)
                    .isEqualTo(com.agentflow.web.run.RunLifecycleStatus.SUCCEEDED);
            assertThat(firstTerminal.usage()).as("five model decisions plus one compaction")
                    .isEqualTo(new com.agentflow.core.chat.TokenUsage(600, 80104, 80704));
            var second = coordinator.create("owner", "Offline fixture: continue with the preserved history", session);
            lastRun = second.taskId();
            var secondTerminal = awaitTerminal(coordinator, lastRun);
            assertThat(secondTerminal.status())
                    .isEqualTo(com.agentflow.web.run.RunLifecycleStatus.SUCCEEDED);
            assertThat(secondTerminal.usage()).as("second run includes its own compaction exactly once")
                    .isEqualTo(new com.agentflow.core.chat.TokenUsage(600, 80104, 80704));
            var fixture = app.getBean(OfflineHarnessFixture.class);
            assertThat(fixture.summaries).hasSize(2);
            assertThat(fixture.secondRunStartedWithSummary).isTrue();
            assertThat(OfflineHarnessFixture.executions).hasValue(1);
            var jdbc = app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from agent_context_checkpoint where session_id=?", Integer.class, session)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from agent_history_record where session_id=? and kind='TOOL_EXECUTION_INTENT'", Integer.class, session)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from agent_history_record where session_id=? and kind='RUN_TERMINAL'", Integer.class, session)).isEqualTo(2);
            artifact = jdbc.queryForObject("select artifact_id from agent_history_record where session_id=? and kind='TOOL_RESULT'", String.class, session);
            assertThat(artifact).isNotBlank();
            var scope = new com.agentflow.core.context.HistoryStore.Scope("owner", session, lastRun);
            var store = app.getBean(com.agentflow.core.artifact.ArtifactStore.class);
            assertThat(store.get(scope, artifact, io()).savedBytes()).isGreaterThan(65536);
            var state = app.getBean(com.agentflow.core.context.HistoryStore.class).state(scope, io());
            lastSequence = state.lastSequence(); coverage = state.checkpoint().coveredThrough();
            assertThat(state.checkpoint().retainedTokens()).isLessThanOrEqualTo(state.checkpoint().sourceTokens() / 5);
            assertThat(state.checkpoint().summary()).contains(artifact);
        }
        try (var app = startHarness()) {
            var scope = new com.agentflow.core.context.HistoryStore.Scope("owner", session, lastRun);
            var state = app.getBean(com.agentflow.core.context.HistoryStore.class).state(scope, io());
            assertThat(state.lastSequence()).isEqualTo(lastSequence);
            assertThat(state.checkpoint().coveredThrough()).isEqualTo(coverage);
            var sessions = app.getBean(com.agentflow.web.context.ContextSessionService.class);
            try (var restored = sessions.open(scope, io())) {
                var messages = restored.prepare(app.getBean(com.agentflow.core.tool.ToolRegistry.class).enabledDefinitions(), 32768, 1);
                assertThat(messages).anyMatch(message -> message.content().contains("offline summary 2"));
                assertThat(messages).noneMatch(message -> message.content().contains("detail ".repeat(12000)));
            }
            var read = app.getBean(com.agentflow.core.artifact.ArtifactStore.class).read(scope, artifact, 0, 65536, 8192, io());
            assertThat(read.content()).startsWith("detail ");
            assertThat(read.eof()).isFalse();
            assertThat(OfflineHarnessFixture.executions).hasValue(1);
            assertThat(app.getBean(OfflineHarnessFixture.class).requests).isEmpty();
        }
    }
    private static com.agentflow.web.run.RunSnapshot awaitTerminal(com.agentflow.web.run.RunCoordinator coordinator, String task) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var result = coordinator.getOwned("owner", task);
                if (result.status().isTerminal()) return result;
            } catch (com.agentflow.web.run.RunCoordinator.RunUnavailableException pending) { /* Short fact commit may be pending. */ }
            Thread.sleep(20);
        }
        throw new AssertionError("offline Harness did not reach a committed terminal state");
    }
    private org.springframework.context.ConfigurableApplicationContext startHarness() {
        return new SpringApplicationBuilder(HarnessApp.class).profiles("local-harness")
                .properties("spring.main.banner-mode=off").run("--server.port=0",
                        "--agentflow.harness.enabled=true", "--agentflow.harness.storage.data-root=" + directory.toString().replace('\\','/'),
                        "--agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32",
                        "--agentflow.harness.context.window-tokens=100000", "--agentflow.harness.context.safety-tokens=512",
                        "--agentflow.harness.context.recent-tokens=1024", "--agentflow.harness.context.output-tokens=32768",
                        "--spring.autoconfigure.exclude=com.agentflow.mcp.McpAutoConfiguration,com.agentflow.rag.AgentRagAutoConfiguration");
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    static class HarnessApp {
        @org.springframework.context.annotation.Bean OfflineHarnessFixture offlineHarnessFixture() { return new OfflineHarnessFixture(); }
        @org.springframework.context.annotation.Bean com.agentflow.core.model.AgentModelClient offlineModel(OfflineHarnessFixture fixture) { return fixture::decide; }
        @org.springframework.context.annotation.Bean com.agentflow.core.context.CompactionModel offlineSummary(OfflineHarnessFixture fixture) { return fixture::summarize; }
        @org.springframework.context.annotation.Bean com.agentflow.core.tool.AgentTool offlineLargeTool() {
            return new com.agentflow.core.tool.AgentTool() {
                public com.agentflow.core.tool.ToolDefinition definition() {
                    return new com.agentflow.core.tool.ToolDefinition("offline-large-output", "Explicit offline fixture large result", com.agentflow.core.tool.RiskLevel.LOW,
                            new com.agentflow.core.tool.ToolSchema(java.util.Map.of()));
                }
                public com.agentflow.core.tool.ToolResult execute(com.agentflow.core.tool.ToolArguments arguments, com.agentflow.core.tool.ToolContext context) {
                    OfflineHarnessFixture.executions.incrementAndGet();
                    return com.agentflow.core.tool.ToolResult.success("offline-large-output", "detail ".repeat(12000));
                }
            };
        }
        @org.springframework.context.annotation.Bean com.agentflow.core.tool.ToolExecutionPolicy offlinePolicy() {
            return name -> java.util.Set.of("offline-large-output", "read-artifact").contains(name)
                    ? new com.agentflow.core.tool.ToolPolicyDecision(com.agentflow.core.tool.ToolPolicyDecision.Action.ALLOW,
                        com.agentflow.core.tool.RiskLevel.LOW, com.agentflow.core.tool.ToolPolicyDecision.Effect.READ_ONLY,
                        "Explicit offline test read-only tool", java.util.Set.of())
                    : com.agentflow.core.tool.ToolPolicyDecision.denied();
        }
        @org.springframework.context.annotation.Bean com.agentflow.core.model.EmbeddingClient unusedEmbedding() { return text -> { throw new UnsupportedOperationException("not an embedding scenario"); }; }
        @org.springframework.context.annotation.Bean com.agentflow.core.chat.ChatModelClient unusedChat() {
            return new com.agentflow.core.chat.ChatModelClient() {
                public com.agentflow.core.chat.ChatCompletionResponse complete(com.agentflow.core.chat.ChatCompletionRequest request) { throw new UnsupportedOperationException("not a chat scenario"); }
                public com.agentflow.core.chat.ChatCompletionResponse stream(com.agentflow.core.chat.ChatCompletionRequest request, java.util.function.Consumer<String> sink) { throw new UnsupportedOperationException("not a chat scenario"); }
            };
        }
    }
    /** Deterministic, explicitly test-only model; all lifecycle, storage and tools are real. */
    static final class OfflineHarnessFixture {
        static final java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<com.agentflow.core.model.AgentModelRequest> requests = new java.util.ArrayList<>();
        final java.util.List<com.agentflow.core.context.CompactionModel.Request> summaries = new java.util.ArrayList<>();
        String artifactId; boolean secondRunStartedWithSummary;
        com.agentflow.core.model.ModelDecision decide(com.agentflow.core.model.AgentModelRequest request) {
            boolean second = request.input().contains("continue with");
            if (second && request.iteration() == 1) secondRunStartedWithSummary = request.messages().stream().anyMatch(message -> message.content().contains("offline summary 1"));
            requests.add(request);
            if (request.iteration() == 5) return new com.agentflow.core.model.FinalAnswerDecision("offline-final-" + request.taskId(), "Offline fixture complete", new com.agentflow.core.chat.TokenUsage(100, 4, 104));
            var items = new java.util.ArrayList<com.agentflow.core.model.ModelMessage>();
            items.add(com.agentflow.core.model.ModelMessage.assistant("note ".repeat(20000)));
            var action = com.agentflow.core.model.AssistantTurnDecision.NextAction.CONTINUE;
            if (!second && request.iteration() == 1) {
                items.add(com.agentflow.core.model.ModelMessage.assistantToolCall(new com.agentflow.core.tool.ToolCall("offline-call", "offline-large-output", new com.agentflow.core.tool.ToolArguments(java.util.Map.of()))));
                action = com.agentflow.core.model.AssistantTurnDecision.NextAction.TOOL_CALL;
            }
            return new com.agentflow.core.model.AssistantTurnDecision("offline-decision-" + request.taskId() + "-" + request.iteration(), items, action,
                    new com.agentflow.core.chat.TokenUsage(100, 20000, 20100), com.agentflow.core.model.UsageSource.REPORTED, null, null);
        }
        com.agentflow.core.context.CompactionModel.Result summarize(com.agentflow.core.context.CompactionModel.Request request) {
            summaries.add(request);
            for (var message : request.source()) {
                var matcher = java.util.regex.Pattern.compile("\\[Artifact ([^;]+);").matcher(message.content());
                if (matcher.find()) artifactId = matcher.group(1);
            }
            assertThat(artifactId).isNotBlank();
            String summary = "{\"goal\":\"offline summary " + summaries.size() + "\",\"constraints\":[],\"userCorrections\":[],\"decisions\":[],\"completedFacts\":[\"large tool ran once\"],\"remainingWork\":[],\"blockers\":[],\"uncertainActions\":[],\"references\":[{\"kind\":\"ARTIFACT\",\"id\":\"" + artifactId + "\",\"availability\":\"AVAILABLE\",\"note\":\"read by range\"}],\"continuationState\":\"continue\"}";
            return new com.agentflow.core.context.CompactionModel.Result(true, summary, new com.agentflow.core.chat.TokenUsage(100, 100, 200), com.agentflow.core.model.UsageSource.REPORTED, null);
        }
    }
}
