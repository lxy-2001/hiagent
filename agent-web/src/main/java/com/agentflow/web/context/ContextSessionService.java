package com.agentflow.web.context;

import com.agentflow.core.artifact.ArtifactStore;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Durable records and the bounded model window have different lifetimes. */
public class ContextSessionService {
    private static final long SESSION_BYTES = 16L * 1024 * 1024;
    private final HistoryStore store;
    private final TokenEstimator estimator;
    private final ContextPolicy policy;
    private final ContextCompactor compactor;
    private final ContextCompactor.Limits limits;
    private final ArtifactStore artifacts;
    private final String profileHash;
    private final Consumer<Observation> observer;
    private final CacheLimits cacheLimits;
    private final BiConsumer<HistoryStore.Scope, HistoryStore.Io> recovery;
    public record CacheLimits(int sessions, long sessionBytes, long totalBytes) {
        public CacheLimits {
            if (sessions < 1 || sessionBytes < 1 || sessionBytes > 16L * 1024 * 1024
                    || totalBytes < sessionBytes) throw new IllegalArgumentException("invalid context cache limits");
        }
        public static CacheLimits defaults() { return new CacheLimits(4, SESSION_BYTES, 64L * 1024 * 1024); }
    }
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(8, .75f, true);
    private final Map<String, HistoryStore.Io> activeRuns = new HashMap<>();
    public record Observation(HistoryStore.Scope scope, String requestKey, String purpose, long estimatedInputTokens,
            int outputReserveTokens, int safetyTokens, Long sourceHistoryTokens, Long retainedHistoryTokens, String failureCode) { }
    private static final class Entry { WorkingContext window; boolean active; }

    public ContextSessionService(HistoryStore store, TokenEstimator estimator, ContextPolicy policy) {
        this(store, estimator, policy, null, null, null, policy.promptVersion(), observation -> { });
    }
    public ContextSessionService(HistoryStore store, TokenEstimator estimator, ContextPolicy policy,
            ContextCompactor compactor, ContextCompactor.Limits limits, ArtifactStore artifacts,
            String profileHash, Consumer<Observation> observer) {
        this(store, estimator, policy, compactor, limits, artifacts, profileHash, observer,
                CacheLimits.defaults(), (scope, io) -> { });
    }
    public ContextSessionService(HistoryStore store, TokenEstimator estimator, ContextPolicy policy,
            ContextCompactor compactor, ContextCompactor.Limits limits, ArtifactStore artifacts,
            String profileHash, Consumer<Observation> observer, CacheLimits cacheLimits,
            BiConsumer<HistoryStore.Scope, HistoryStore.Io> recovery) {
        this.store = Objects.requireNonNull(store); this.estimator = Objects.requireNonNull(estimator);
        this.policy = Objects.requireNonNull(policy); this.compactor = compactor; this.limits = limits;
        this.artifacts = artifacts; this.profileHash = Objects.requireNonNull(profileHash); this.observer = Objects.requireNonNull(observer);
        this.cacheLimits = Objects.requireNonNull(cacheLimits); this.recovery = Objects.requireNonNull(recovery);
    }
    public int cachedSessions() { synchronized (cache) { return cache.size(); } }
    public HistoryStore.Io io(String runId) {
        synchronized (cache) { var io = activeRuns.get(runId); if (io == null) throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED"); return io; }
    }
    public ContextSession open(HistoryStore.Scope scope, HistoryStore.Io io) { return open(scope, io, List.of()); }
    public ContextSession open(HistoryStore.Scope scope, HistoryStore.Io io, List<ModelMessage> taskAuthority) {
        String key = scope.userId() + ":" + scope.sessionId(); Entry entry;
        synchronized (cache) {
            entry = cache.get(key);
            if (entry != null && entry.active) throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
            if (entry == null) {
                // Reserve a full window per entry before restoration can allocate memory.
                long capacity = Math.min(cacheLimits.sessions(), cacheLimits.totalBytes() / cacheLimits.sessionBytes());
                if (cache.size() >= capacity) {
                    var victim = cache.entrySet().stream().filter(e -> !e.getValue().active).findFirst()
                            .orElseThrow(() -> new ContextSessionException("CONTEXT_BUDGET_EXCEEDED"));
                    cache.remove(victim.getKey());
                }
                entry = new Entry(); cache.put(key, entry);
            }
            entry.active = true; activeRuns.put(scope.runId(), io);
        }
        try {
            recovery.accept(scope, io);
            if (entry.window == null) entry.window = WorkingContext.restore(scope, store, io, cacheLimits.sessionBytes());
            else entry.window.refresh(scope, store, io);
            return new Session(scope, io, entry, taskAuthority);
        } catch (RuntimeException failure) {
            synchronized (cache) { entry.active = false; cache.remove(key); activeRuns.remove(scope.runId()); }
            throw storageFailure(failure);
        }
    }
    public void invalidate(String owner, String session) {
        synchronized (cache) { var entry = cache.get(owner + ":" + session); if (entry != null && entry.active) throw new IllegalStateException("SESSION_BUSY"); cache.remove(owner + ":" + session); }
    }
    private final class Session implements ContextSession {
        private final HistoryStore.Scope scope; private final HistoryStore.Io io; private final Entry entry;
        private final List<ModelMessage> authority; private final List<AutoCloseable> pins = new ArrayList<>();
        private TokenUsage preparationUsage = TokenUsage.empty(); private boolean closed;
        Session(HistoryStore.Scope scope, HistoryStore.Io io, Entry entry, List<ModelMessage> taskAuthority) {
            this.scope = scope; this.io = io; this.entry = entry;
            var messages = new ArrayList<ModelMessage>(); messages.add(new ModelMessage("system", policy.systemText()));
            messages.addAll(taskAuthority); authority = List.copyOf(messages);
        }
        public HistoryStore.Scope scope() { return scope; }
        public List<ModelMessage> prepare(List<ToolDefinition> tools, int remaining, int iteration) {
            return prepare(tools, remaining, Long.MAX_VALUE, iteration);
        }
        public List<ModelMessage> prepare(List<ToolDefinition> tools, int remaining, long remainingInput, int iteration) {
            checkIo(io); List<ModelMessage> messages;
            int output = limits == null ? Math.min(32768, remaining) : Math.min(limits.outputTokens(), remaining);
            int safety = limits == null ? 8192 : limits.safetyTokens();
            if (compactor != null) {
                var attempts = new ArrayList<ContextCompactor.Attempt>();
                var prepared = compactor.prepare(entry.window, scope, store, io, authority, tools, limits, profileHash, remaining, remainingInput, attempt -> {
                    attempts.add(attempt);
                    var result = attempt.result();
                    TokenUsage charged = result != null && result.usageSource() == UsageSource.REPORTED && result.usage() != null
                            ? result.usage() : new TokenUsage(Math.toIntExact(attempt.estimatedInputTokens()), attempt.outputTokens(),
                                    Math.toIntExact(attempt.estimatedInputTokens() + attempt.outputTokens()));
                    preparationUsage = add(preparationUsage, charged);
                    observer.accept(new Observation(scope, attempt.requestKey(), "COMPACTION", attempt.estimatedInputTokens(),
                            attempt.outputTokens(), safety, null, null, attempt.failureCode()));
                });
                messages = prepared.messages(); var diagnostic = prepared.diagnostics();
                if (!attempts.isEmpty()) { var last = attempts.get(attempts.size()-1);
                    observer.accept(new Observation(scope, last.requestKey(), "COMPACTION", last.estimatedInputTokens(), last.outputTokens(), safety,
                            diagnostic.sourceHistoryTokens(), diagnostic.retainedHistoryTokens(), diagnostic.failureCode())); }
            } else {
                messages = entry.window.messages(authority);
                if (estimator.estimateInput(messages, tools) + output + safety > policy.windowLimit()) throw new ContextSessionException("CONTEXT_BUDGET_EXCEEDED");
            }
            long estimated = estimator.estimateInput(messages, tools);
            observer.accept(new Observation(scope, "decision:" + scope.runId() + ":" + iteration, "MODEL_DECISION", estimated, output, safety, null, null, null));
            return messages;
        }
        public TokenUsage drainPreparationUsage() { var usage = preparationUsage; preparationUsage = TokenUsage.empty(); return usage; }
        public void confirm(ModelDecision decision) {
            List<ModelMessage> messages; String ref = null;
            if (decision instanceof AssistantTurnDecision turn) { messages = turn.orderedItems(); ref = turn.continuationRef(); }
            else if (decision instanceof ToolCallDecision call) messages = List.of(ModelMessage.assistantToolCall(call.toolCall()));
            else messages = List.of(ModelMessage.assistant(((FinalAnswerDecision)decision).answer()));
            var items = new ArrayList<HistoryStore.Item>();
            for (var message : messages) {
                var projected = ref == null ? message : message.withAdapterStateRef(ref);
                items.add(new HistoryStore.Item(message.toolCall() == null ? HistoryStore.Kind.ASSISTANT_MESSAGE : HistoryStore.Kind.TOOL_CALL,
                        projected, Instant.now(), decision.decisionId(), message.toolCallId(), null, null, ref));
            }
            if (!items.isEmpty()) append("decision:" + scope.runId() + ":" + decision.decisionId(), items, factIo());
        }
        public void intent(ToolCall call) { append("intent:" + scope.runId() + ":" + call.callId(), List.of(new HistoryStore.Item(
                HistoryStore.Kind.TOOL_EXECUTION_INTENT, null, Instant.now(), null, call.callId(), "INTENT", null, null))); }
        public ToolResult result(ToolResult result) {
            HistoryStore.Io factWrite = factIo(); String artifactId = null; ToolResult projected = result;
            String text = result.status() == ToolResultStatus.SUCCESS ? result.outputOrEmpty()
                    : "FAILED [" + result.errorCode() + "]: " + Objects.toString(result.diagnostic(), "");
            // Retrieval evidence keeps the existing runtime ledger and its separate strict bound.
            if (result.retrievalPayload() == null && !"read-artifact".equals(result.toolName()) && artifacts != null
                    && (text.length() > 65536 || estimator.estimateInput(List.of(ModelMessage.user(text)), List.of()) > 4096)) {
                ArtifactStore.Ref ref = artifacts.save(scope, "TOOL_OUTPUT", text, result.truncated(), factWrite);
                if (ref.state() != ArtifactStore.State.AVAILABLE && ref.state() != ArtifactStore.State.TRUNCATED) throw new ContextSessionException("ARTIFACT_STORAGE_FAILED");
                artifactId = ref.id(); pins.add(artifacts.pin(scope, ref.id()));
                text = ref.preview() + "\n[Artifact " + ref.id() + "; state=" + ref.state() + "; savedBytes=" + ref.savedBytes()
                        + "; truncated=" + (result.truncated() || ref.truncated()) + "; use read-artifact for details]";
                projected = new ToolResult(result.toolName(), text, result.status(), result.errorCode(), result.diagnostic(),
                        result.truncated() || ref.truncated(), result.callId(), null);
            }
            var message = new ModelMessage("tool", text, result.toolName(), result.callId(), null);
            append("result:" + scope.runId() + ":" + result.callId(), List.of(new HistoryStore.Item(HistoryStore.Kind.TOOL_RESULT,
                    message, Instant.now(), null, result.callId(), result.status().name(), artifactId, null)), factWrite);
            return projected;
        }
        private void append(String key, List<HistoryStore.Item> items) { append(key, items, io); }
        private void append(String key, List<HistoryStore.Item> items, HistoryStore.Io writeIo) {
            try { var batch = store.appendBatch(scope, key, hash(items), items, writeIo); entry.window.append(batch, items, scope.runId()); }
            catch (RuntimeException failure) { throw storageFailure(failure); }
        }
        public void close() {
            if (closed) return; closed = true;
            for (AutoCloseable pin : pins) try { pin.close(); } catch (Exception ignored) { /* no execution is replayed during cleanup */ }
            synchronized (cache) { entry.active = false; activeRuns.remove(scope.runId()); }
        }
    }
    /** Saving a fact already obtained never starts a new model or tool action. */
    public static HistoryStore.Io factIo() {
        return new HistoryStore.Io(com.agentflow.core.cancel.CancellationSignal.NONE, Instant.now().plusSeconds(10));
    }
    private static TokenUsage add(TokenUsage first, TokenUsage second) {
        return new TokenUsage(Math.addExact(first.promptTokens(), second.promptTokens()), Math.addExact(first.completionTokens(), second.completionTokens()), Math.addExact(first.totalTokens(), second.totalTokens()));
    }
    private static void checkIo(HistoryStore.Io io) { try { io.check(); } catch (RuntimeException failure) { throw storageFailure(failure); } }
    private static ContextSessionException storageFailure(RuntimeException failure) {
        if (failure instanceof ContextSessionException known) return known;
        String code = "CANCELLED".equals(failure.getMessage()) ? "CANCELLED" : "TIMED_OUT".equals(failure.getMessage()) ? "TIMED_OUT" : "HISTORY_PERSISTENCE_FAILED";
        return new ContextSessionException(code, failure);
    }
    private static String hash(List<HistoryStore.Item> items) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(items.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
