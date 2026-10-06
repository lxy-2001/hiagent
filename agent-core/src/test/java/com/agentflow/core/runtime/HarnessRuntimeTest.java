package com.agentflow.core.runtime;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.ContextSession;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessRuntimeTest {
    @Test
    void durableSessionReceivesOrderedDecisionAndToolFactsWithoutReplayingTool() {
        List<String> facts = new ArrayList<>();
        ContextSession session = new ContextSession() {
            private final HistoryStore.Scope scope = new HistoryStore.Scope("u", "s", "r");
            @Override public HistoryStore.Scope scope() { return scope; }
            @Override public List<ModelMessage> prepare(List<com.agentflow.core.tool.ToolDefinition> tools,
                                                        int remainingOutputTokens, int iteration) {
                facts.add("prepare-" + iteration);
                return List.of(ModelMessage.user("input"));
            }
            @Override public void confirm(ModelDecision decision) { facts.add("decision-" + decision.decisionId()); }
            @Override public void intent(ToolCall call) { facts.add("intent-" + call.callId()); }
            @Override public ToolResult result(ToolResult result) { facts.add("result-" + result.callId()); return result; }
        };
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> ToolResult.success("echo", "ok"));
        var registry = RuntimeTestSupport.registry(tool);
        var model = new com.agentflow.core.model.AgentModelClient() {
            int calls;
            @Override public com.agentflow.core.model.ModelDecision decide(AgentModelRequest request) {
                calls++;
                return calls == 1
                        ? new ToolCallDecision("d1", new ToolCall("c1", "echo", new ToolArguments(Map.of())), TokenUsage.empty())
                        : new FinalAnswerDecision("d2", "done", TokenUsage.empty());
            }
        };
        var runtime = RuntimeTestSupport.runtime(model, registry, new com.agentflow.core.tool.DefaultToolExecutor(registry),
                step -> { }, null, TimeSource.system(),
                new com.agentflow.core.context.ContextAssembler(com.agentflow.core.context.ContextPolicy.defaults(),
                        new com.agentflow.core.context.Utf8TokenEstimator(), new com.agentflow.core.context.ContextTextPolicy()));

        var options = new AgentRunOptions(ExecutionBudget.defaults(), com.agentflow.core.cancel.CancellationSignal.NONE,
                null, java.time.Duration.ofSeconds(30), session);
        var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> { }, options);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("prepare-1", "decision-d1", "intent-c1", "result-c1", "prepare-2", "decision-d2"), facts);
    }
    @Test
    void modelBoundaryPreservesDurabilityCancellationAndTimeoutReasons() {
        for (String code : List.of("HISTORY_PERSISTENCE_FAILED", "CANCELLED", "TIMED_OUT")) {
            int[] tools = {0};
            var registry = RuntimeTestSupport.registry(RuntimeTestSupport.tool("echo", (args, context) -> { tools[0]++; return ToolResult.success("echo", "ok"); }));
            var runtime = RuntimeTestSupport.runtime(request -> { throw new com.agentflow.core.context.ContextSessionException(code); }, registry, step -> {});
            var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(new RecordingSession()));
            assertEquals(TerminationReason.valueOf(code), result.terminationReason());
            assertEquals(0, tools[0]);
        }
    }
    @Test
    void unconfirmedDecisionNeverDispatchesTool() {
        int[] tools = {0}; var session = new RecordingSession(); session.failureAt = "decision";
        var registry = RuntimeTestSupport.registry(RuntimeTestSupport.tool("echo", (args, context) -> { tools[0]++; return ToolResult.success("echo", "ok"); }));
        var runtime = RuntimeTestSupport.runtime(r -> callDecision(), registry, step -> {});
        var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(session));
        assertEquals(TerminationReason.HISTORY_PERSISTENCE_FAILED, result.terminationReason()); assertEquals(0, tools[0]);
    }

    @Test
    void unconfirmedResultDoesNotReplayToolOrStartNextModel() {
        int[] tools = {0}; int[] models = {0}; var session = new RecordingSession(); session.failureAt = "result";
        var registry = RuntimeTestSupport.registry(RuntimeTestSupport.tool("echo", (args, context) -> { tools[0]++; return ToolResult.success("echo", "ok"); }));
        var runtime = RuntimeTestSupport.runtime(r -> { models[0]++; return callDecision(); }, registry, step -> {});
        var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(session));
        assertEquals(TerminationReason.HISTORY_PERSISTENCE_FAILED, result.terminationReason()); assertEquals(1, tools[0]); assertEquals(1, models[0]);
    }

    @Test
    void rejectedToolAndModelFailureAreDurableFacts() {
        var session = new RecordingSession(); var registry = RuntimeTestSupport.registry();
        var runtime = RuntimeTestSupport.runtime(r -> callDecision(), registry, step -> {});
        var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(session));
        assertEquals(TerminationReason.UNKNOWN_TOOL, result.terminationReason()); assertTrue(session.facts.contains("result-FAILED"));
        var failedModelSession = new RecordingSession();
        var failedModel = RuntimeTestSupport.runtime(r -> { throw new IllegalStateException("offline"); }, registry, step -> {});
        result = failedModel.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(failedModelSession));
        assertEquals(TerminationReason.MODEL_ERROR, result.terminationReason()); assertTrue(failedModelSession.facts.contains("MODEL_ERROR"));
    }

    @Test
    void visibleContinueAndFinalAreConfirmedInNormalLoop() {
        var session = new RecordingSession(); var registry = RuntimeTestSupport.registry(); int[] calls = {0};
        var runtime = RuntimeTestSupport.runtime(r -> ++calls[0] == 1
                ? new com.agentflow.core.model.AssistantTurnDecision("d1", List.of(ModelMessage.assistant("继续分析")),
                    com.agentflow.core.model.AssistantTurnDecision.NextAction.CONTINUE, TokenUsage.empty(), com.agentflow.core.model.UsageSource.UNKNOWN, null, null)
                : new com.agentflow.core.model.AssistantTurnDecision("d2", List.of(ModelMessage.assistant("完成")),
                    com.agentflow.core.model.AssistantTurnDecision.NextAction.FINAL, TokenUsage.empty(), com.agentflow.core.model.UsageSource.UNKNOWN, null, null), registry, step -> {});
        var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(session));
        assertEquals(RunStatus.SUCCEEDED, result.status()); assertEquals("完成", result.finalAnswer()); assertEquals(2, calls[0]); assertEquals(2, session.decisions.size());
    }

    @Test
    void checkpointTailRestorationIsBoundedAndHotAppendNeverReloadsHistory() {
        var store = new com.agentflow.core.context.MemoryHistoryStore();
        store.add("old", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("covered goal"), null));
        store.add("old", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("covered answer"), null));
        var interrupted = new ToolCall("pending", "echo", new ToolArguments(Map.of()));
        store.add("old", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(interrupted), "pending"));
        store.add("r", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("new input"), null));
        store.checkpoint = new HistoryStore.Checkpoint("cp", 1, null, 2, "old compacted facts", 20, 100, 20, 3, "key", "v1", "profile", Instant.EPOCH);
        var io = new HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE, Instant.now().plusSeconds(30));
        var working = com.agentflow.core.context.WorkingContext.restore(new HistoryStore.Scope("u", "s", "r"), store, io, 16777216);
        var messages = working.messages();
        assertEquals(2, store.requestedAfter);
        assertTrue(messages.stream().noneMatch(m -> m.content().contains("covered answer")));
        assertTrue(messages.stream().anyMatch(m -> m.role().equals("tool") && m.toolCallId().equals("pending") && m.content().contains("UNKNOWN")));
        int reads = store.reads;
        var item = com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.ASSISTANT_MESSAGE, ModelMessage.assistant("new response"), null);
        var batch = store.appendBatch(new HistoryStore.Scope("u", "s", "r"), "append", "hash", List.of(item), io);
        working.append(batch, List.of(item), "r");
        assertEquals(reads, store.reads);
        assertEquals("new response", working.messages().get(working.messages().size()-1).content());
    }

    @Test
    void restorationRejectsOversizedTailInsteadOfLoadingWholeConversation() {
        var store = new com.agentflow.core.context.MemoryHistoryStore();
        store.add("old", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("x".repeat(1024)), null));
        var io = new HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE, Instant.now().plusSeconds(30));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(com.agentflow.core.context.ContextSessionException.class,
                () -> com.agentflow.core.context.WorkingContext.restore(new HistoryStore.Scope("u", "s", "r"), store, io, 512));
        assertEquals("CONTEXT_BUDGET_EXCEEDED", failure.code());
        assertEquals(1, store.reads);
    }

    @Test
    void summaryUsageCountsBeforeNextDecisionEvenWhenPreparationFails() {
        int[] calls = {0};
        var session = new RecordingSession() {
            boolean drained;
            @Override public List<ModelMessage> prepare(List<com.agentflow.core.tool.ToolDefinition> tools, int remaining, int iteration) {
                throw new com.agentflow.core.context.ContextSessionException("CONTEXT_COMPACTION_FAILED");
            }
            @Override public TokenUsage drainPreparationUsage() {
                if (drained) return TokenUsage.empty();
                drained = true; return new TokenUsage(100, 20, 120);
            }
        };
        var runtime = RuntimeTestSupport.runtime(r -> { calls[0]++; return callDecision(); }, RuntimeTestSupport.registry(), step -> {});
        var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(session));
        assertEquals(TerminationReason.CONTEXT_COMPACTION_FAILED, result.terminationReason());
        assertEquals(new TokenUsage(100, 20, 120), result.usage()); assertEquals(0, calls[0]);
    }

    @Test
    void betweenRunsRefreshOnlyReadsNewConfirmedRecordsAndKeepsWindowOnRejectedCheckpoint() {
        var store = new com.agentflow.core.context.MemoryHistoryStore();
        var scope = new HistoryStore.Scope("u", "s", "r");
        var io = new HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE, Instant.now().plusSeconds(60));
        store.add("old", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("old"), null));
        var working = com.agentflow.core.context.WorkingContext.restore(scope, store, io, 4096);
        store.add("r", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("new"), null));
        working.refresh(scope, store, io);
        assertEquals(1, store.requestedAfter); assertEquals(2, working.lastSequence());
        assertEquals(List.of(ModelMessage.user("old"), ModelMessage.user("new")), working.messages());
        var before = working.messages();
        var oversized = new HistoryStore.Checkpoint("huge", 3, null, 1, "x".repeat(4096), 4096, 8192, 4096, 2, "key", "v1", "profile", Instant.EPOCH);
        org.junit.jupiter.api.Assertions.assertThrows(com.agentflow.core.context.ContextSessionException.class, () -> working.applyCheckpoint(oversized));
        assertEquals(before, working.messages());
    }

    @Test
    void managedToolOutputReachesSessionBeforePreviewAndLongArgumentsRemainIntact() {
        String large = "safe output\n".repeat(1500);
        String parameter = "long original file " .repeat(1000);
        List<ToolResult> captured = new ArrayList<>();
        var session = new RecordingSession() {
            @Override public ToolResult result(ToolResult result) {
                captured.add(result);
                return new ToolResult(result.toolName(), "preview with artifact reference", com.agentflow.core.tool.ToolResultStatus.SUCCESS, null, null, false, result.callId(), null);
            }
        };
        var registry = RuntimeTestSupport.registry(new com.agentflow.core.tool.AgentTool() {
            @Override public com.agentflow.core.tool.ToolDefinition definition() {
                return new com.agentflow.core.tool.ToolDefinition("echo", "echo", com.agentflow.core.tool.RiskLevel.LOW,
                        new com.agentflow.core.tool.ToolSchema(Map.of("text", com.agentflow.core.tool.ParameterSpec.requiredString(30000)), java.util.Set.of("text"), false));
            }
            @Override public ToolResult execute(ToolArguments args, com.agentflow.core.tool.ToolContext context) {
                assertEquals(parameter, args.values().get("text"));
                return ToolResult.success("echo", large);
            }
        });
        int[] calls = {0};
        var runtime = RuntimeTestSupport.runtime(r -> ++calls[0] == 1
                ? new ToolCallDecision("d1", new ToolCall("c", "echo", ToolArguments.managed(Map.of("text", parameter))), TokenUsage.empty())
                : new FinalAnswerDecision("d2", "done", TokenUsage.empty()), registry, step -> {});
        var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options(session));
        assertEquals(RunStatus.SUCCEEDED, result.status(), () -> result.terminationReason() + " " + result.diagnostic() + " " + result.steps());
        assertEquals(1, captured.size());
        assertEquals(large, captured.get(0).output());
        assertEquals(com.agentflow.core.tool.ToolResultStatus.SUCCESS, captured.get(0).status());
    }

    @Test
    void restoredAssistantCarriesOnlyOpaqueAdapterReferenceAndRecoveryPairsWithOldCall() throws Exception {
        var store = new com.agentflow.core.context.MemoryHistoryStore();
        var call = new ToolCall("pending", "echo", new ToolArguments(Map.of()));
        var time = Instant.EPOCH;
        store.add("old", new HistoryStore.Item(HistoryStore.Kind.TOOL_CALL, ModelMessage.assistantToolCall(call), time,
                "decision", "pending", null, null, "opaque-ref"));
        store.add("r", com.agentflow.core.context.MemoryHistoryStore.message(HistoryStore.Kind.USER_MESSAGE, ModelMessage.user("new request"), null));
        store.add("old", new HistoryStore.Item(HistoryStore.Kind.RECOVERY_NOTICE,
                new ModelMessage("tool", "UNKNOWN operation outcome", "echo", "pending", null), time,
                null, "pending", "UNKNOWN", null, null));
        var io = new HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE, Instant.now().plusSeconds(30));
        var working = com.agentflow.core.context.WorkingContext.restore(new HistoryStore.Scope("u", "s", "r"), store, io, 16777216);
        var messages = working.messages();
        assertEquals(List.of("assistant", "tool", "user"), messages.stream().map(ModelMessage::role).toList());
        assertEquals("pending", messages.get(1).toolCallId());
        assertEquals("opaque-ref", ModelMessage.class.getMethod("adapterStateRef").invoke(messages.get(0)));
        assertEquals(3, working.sequence());
    }

    @Test
    void confirmedButUndispatchedCallsHaveKnownFailureInsteadOfUnknownRecovery() {
        for (boolean deny : List.of(true, false)) {
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var executed = new java.util.concurrent.atomic.AtomicInteger();
            List<ToolResult> results = new ArrayList<>();
            var session = new RecordingSession() {
                @Override public void confirm(ModelDecision decision) {
                    super.confirm(decision);
                    if (!deny) cancelled.set(true);
                }
                @Override public ToolResult result(ToolResult result) {
                    results.add(result); return super.result(result);
                }
            };
            var registry = RuntimeTestSupport.registry(RuntimeTestSupport.tool("echo", (args, context) -> {
                executed.incrementAndGet(); return ToolResult.success("echo", "ok");
            }));
            var runtime = deny ? new DefaultAgentRuntime(r -> callDecision(), registry, step -> {})
                    : RuntimeTestSupport.runtime(r -> callDecision(), registry, step -> {});
            var options = new AgentRunOptions(ExecutionBudget.defaults(), cancelled::get, null,
                    java.time.Duration.ofSeconds(30), session);
            var result = runtime.run(new AgentRequest("r", "s", "u", "input"), event -> {}, options);
            assertEquals(deny ? TerminationReason.TOOL_POLICY_DENIED : TerminationReason.CANCELLED,
                    result.terminationReason());
            assertEquals(0, executed.get());
            assertEquals(1, results.size(), "a confirmed call that definitely never dispatched needs its true outcome");
            assertEquals(deny ? "TOOL_POLICY_DENIED" : "CANCELLED", results.get(0).errorCode());
            assertEquals("c", results.get(0).callId());
            assertTrue(session.facts.stream().noneMatch("intent"::equals));
        }
    }
    static ModelDecision callDecision() { return new ToolCallDecision("d", new ToolCall("c", "echo", new ToolArguments(Map.of())), TokenUsage.empty()); }
    static AgentRunOptions options(ContextSession session) {
        return new AgentRunOptions(ExecutionBudget.defaults(), com.agentflow.core.cancel.CancellationSignal.NONE, null, java.time.Duration.ofSeconds(30), session);
    }
    static class RecordingSession implements ContextSession {
        final List<String> facts = new ArrayList<>(); final List<ModelDecision> decisions = new ArrayList<>(); String failureAt;
        @Override public HistoryStore.Scope scope() { return new HistoryStore.Scope("u", "s", "r"); }
        @Override public List<ModelMessage> prepare(List<com.agentflow.core.tool.ToolDefinition> tools, int remaining, int iteration) { return List.of(ModelMessage.user("input")); }
        @Override public void confirm(ModelDecision decision) { check("decision"); decisions.add(decision); }
        @Override public void intent(ToolCall call) { check("intent"); facts.add("intent"); }
        @Override public ToolResult result(ToolResult result) { check("result"); facts.add("result-" + result.status()); return result; }
        @Override public void modelFailure(String code) { facts.add(code); }
        void check(String at) { if(at.equals(failureAt)) throw new IllegalStateException("storage unavailable"); }
    }
}
