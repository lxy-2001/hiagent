package com.agentflow.core.context;

import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.UsageSource;
import com.agentflow.core.tool.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Compresses a confirmed continuous prefix. A durable checkpoint is the switch boundary. */
public final class ContextCompactor {
    public record Limits(long windowTokens, double compactRatio, int outputTokens, int safetyTokens,
                         double retainedRatio, int summaryTokens, int recentTokens) {
        public Limits {
            if (windowTokens < 1 || outputTokens < 1 || safetyTokens < 0
                    || outputTokens >= windowTokens - safetyTokens
                    || !Double.isFinite(compactRatio) || compactRatio <= 0 || compactRatio >= 1
                    || !Double.isFinite(retainedRatio) || retainedRatio <= 0 || retainedRatio >= 1
                    || summaryTokens < 1 || summaryTokens > 8192 || recentTokens < 0) {
                throw new IllegalArgumentException("invalid context limits");
            }
        }
    }
    public record Diagnostics(long sourceHistoryTokens, long retainedHistoryTokens, String failureCode) { }
    public record Preparation(List<ModelMessage> messages, Diagnostics diagnostics) {
        public Preparation { messages = List.copyOf(messages); }
    }
    public record Attempt(String requestKey, long estimatedInputTokens, int outputTokens,
                          CompactionModel.Result result, String failureCode) { }
    private record Cut(int end, long tailTokens, int summaryBudget) { }
    private final CompactionModel model;
    private final TokenEstimator estimator;
    private final CompactionPrompt prompt;
    private final Predicate<String> validator;

    public ContextCompactor(CompactionModel model, TokenEstimator estimator, CompactionPrompt prompt,
                            Predicate<String> validator) {
        this.model = Objects.requireNonNull(model);
        this.estimator = Objects.requireNonNull(estimator);
        this.prompt = Objects.requireNonNull(prompt);
        this.validator = Objects.requireNonNull(validator);
    }

    public Preparation prepare(WorkingContext working, HistoryStore.Scope scope, HistoryStore store,
                               HistoryStore.Io io, List<ModelMessage> authority, List<ToolDefinition> tools,
                               Limits limits, String profileHash, int remainingOutputTokens,
                               Consumer<Attempt> observer) {
        return prepare(working, scope, store, io, authority, tools, limits, profileHash,
                remainingOutputTokens, Long.MAX_VALUE, observer);
    }

    public Preparation prepare(WorkingContext working, HistoryStore.Scope scope, HistoryStore store,
                               HistoryStore.Io io, List<ModelMessage> authority, List<ToolDefinition> tools,
                               Limits limits, String profileHash, int remainingOutputTokens,
                               long remainingInputTokens, Consumer<Attempt> observer) {
        Objects.requireNonNull(working); Objects.requireNonNull(scope); Objects.requireNonNull(store);
        Objects.requireNonNull(io); Objects.requireNonNull(limits); Objects.requireNonNull(profileHash);
        Objects.requireNonNull(observer);
        authority = List.copyOf(authority); tools = List.copyOf(tools);
        WorkingContext.checkIo(io);
        long input = estimate(working.messages(authority), tools);
        long history = estimate(working.messages(), List.of());
        int reserve = Math.min(limits.outputTokens(), Math.max(0, remainingOutputTokens));
        long hardInput = limits.windowTokens() - reserve - limits.safetyTokens();
        long threshold = Math.min((long) (limits.windowTokens() * limits.compactRatio()), hardInput);
        if (input < threshold) return prepared(working, authority, history, null);
        // Rules, tools and the current original goal are not compressible history.
        if (estimate(authority, tools) >= hardInput) throw new ContextSessionException("CONTEXT_BUDGET_EXCEEDED");
        String failureKey = fingerprint(working, scope, authority, tools, limits, profileHash);
        if (failureKey.equals(working.failedCompactionKey())) {
            return failure(working, authority, tools, history, hardInput, "CONTEXT_COMPACTION_FAILED");
        }
        long retainedTarget = (long) Math.floor(history * limits.retainedRatio());
        Cut cut = selectCut(working, limits, retainedTarget);
        if (cut == null) {
            throw new ContextSessionException("CONTEXT_BUDGET_EXCEEDED");
        }
        List<ModelMessage> source = new ArrayList<>();
        // Only the current goal is supplied as read-only data; rules/memory retain separate authority.
        ModelMessage currentGoal = authority.stream().filter(message -> "user".equals(message.role()))
                .reduce((first, last) -> last).orElse(null);
        if (currentGoal != null) source.add(ModelMessage.user("original_user_goal (read-only task data):\n" + currentGoal.content()));
        source.addAll(working.messagesThrough(cut.end()));
        source = List.copyOf(source);
        List<ModelMessage> tail = working.tailMessages(cut.end(), working.activeRecords().size());
        String accepted = null;
        long summaryTokens = 0;
        long retained = 0;
        String requestKey = null;
        int outputLeft = Math.max(0, remainingOutputTokens);
        long inputLeft = Math.max(0, remainingInputTokens);
        for (int attempt = 0; attempt < 2 && outputLeft > 0 && inputLeft > 0; attempt++) {
            int attemptCap = Math.min(limits.outputTokens(), outputLeft);
            int visibleTarget = Math.min(cut.summaryBudget(), attemptCap);
            WorkingContext.checkIo(io);
            String instruction = prompt.instruction(visibleTarget, attempt == 1);
            List<ModelMessage> requestMessages = new ArrayList<>();
            requestMessages.add(new ModelMessage("system", instruction));
            requestMessages.addAll(source);
            long estimatedInput = estimate(requestMessages, List.of());
            // Reasoning and visible summary share the output allowance. Large source histories
            // may need a smaller allowance even though the visible summary target still fits.
            attemptCap = (int) Math.min(attemptCap,
                    Math.max(0, limits.windowTokens() - estimatedInput - limits.safetyTokens()));
            if (attemptCap < 1) break;
            if (visibleTarget > attemptCap) {
                visibleTarget = attemptCap;
                instruction = prompt.instruction(visibleTarget, attempt == 1);
                requestMessages.set(0, new ModelMessage("system", instruction));
                estimatedInput = estimate(requestMessages, List.of());
                attemptCap = (int) Math.min(attemptCap,
                        Math.max(0, limits.windowTokens() - estimatedInput - limits.safetyTokens()));
            }
            if (attemptCap < visibleTarget) break;
            requestKey = "compact-" + failureKey + "-" + attempt;
            CompactionModel.Result result = null;
            String attemptFailure = null;
            try {
                result = model.summarize(new CompactionModel.Request(scope, source, instruction,
                        visibleTarget, attemptCap, io, requestKey));
            } catch (ContextSessionException failure) {
                if (!"CONTEXT_COMPACTION_FAILED".equals(failure.code())) {
                    observer.accept(new Attempt(requestKey, estimatedInput, attemptCap, null, failure.code()));
                    throw failure;
                }
                attemptFailure = failure.code();
            } catch (RuntimeException failure) {
                // A transport failure is not an opportunity to repeat any original tool.
                attemptFailure = "CONTEXT_COMPACTION_FAILED";
            }
            boolean reported = result != null && result.usageSource() == UsageSource.REPORTED && result.usage() != null;
            int consumed = reported ? result.usage().completionTokens() : attemptCap;
            long inputConsumed = reported ? result.usage().promptTokens() : estimatedInput;
            outputLeft = Math.max(0, outputLeft - consumed);
            inputLeft = Math.max(0, inputLeft - inputConsumed);
            boolean valid = valid(result);
            if (valid) {
                summaryTokens = estimate(List.of(WorkingContext.summaryMessage(result.summary())), List.of());
                List<ModelMessage> retainedMessages = new ArrayList<>();
                retainedMessages.add(WorkingContext.summaryMessage(result.summary())); retainedMessages.addAll(tail);
                retained = estimate(retainedMessages, List.of());
                List<ModelMessage> complete = new ArrayList<>(authority); complete.addAll(retainedMessages);
                valid = summaryTokens <= visibleTarget && retained <= retainedTarget
                        && estimate(complete, tools) <= hardInput;
            }
            observer.accept(new Attempt(requestKey, estimatedInput, attemptCap, result,
                    valid ? null : attemptFailure == null ? "CONTEXT_COMPACTION_FAILED" : attemptFailure));
            WorkingContext.checkIo(io);
            if (valid) { accepted = result.summary(); break; }
        }
        if (accepted == null) {
            if (inputLeft <= 0) throw new ContextSessionException("BUDGET_EXCEEDED");
            working.failedCompactionKey(failureKey);
            return failure(working, authority, tools, history, hardInput, "CONTEXT_COMPACTION_FAILED");
        }
        HistoryStore.Checkpoint previous = working.checkpoint();
        long coveredThrough = working.activeRecords().get(cut.end() - 1).sequence();
        var candidate = new HistoryStore.Checkpoint(UUID.randomUUID().toString(), working.revision() + 1,
                previous == null ? null : previous.id(), coveredThrough, accepted, summaryTokens,
                history, retained, coveredThrough + 1, requestKey, CompactionPrompt.VERSION, profileHash, Instant.now());
        HistoryStore.Checkpoint committed;
        try {
            WorkingContext.checkIo(io);
            committed = store.saveCheckpoint(scope, working.revision(), candidate, io);
        } catch (RuntimeException failure) {
            if (isConflict(failure)) {
                // One reload only. A concurrent confirmation must never be overwritten by this summary.
                try { working.refresh(WorkingContext.restore(scope, store, io, working.maxBytes())); }
                catch (ContextSessionException failure2) { throw failure2; }
                catch (RuntimeException failure2) { throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED", failure2); }
                if (estimate(working.messages(authority), tools) > hardInput) throw new ContextSessionException("CONTEXT_COMPACTION_FAILED");
                return prepared(working, authority, history, "CHECKPOINT_REVISION_CONFLICT");
            }
            if (failure instanceof ContextSessionException sessionFailure) throw sessionFailure;
            throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED", failure);
        }
        if (committed == null || !candidate.id().equals(committed.id())
                || committed.coveredThrough() != coveredThrough || !accepted.equals(committed.summary())) {
            throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
        }
        working.applyCheckpoint(committed);
        return prepared(working, authority, history, null);
    }

    private Cut selectCut(WorkingContext working, Limits limits, long target) {
        List<HistoryStore.Record> records = working.activeRecords();
        Set<String> openCalls = new HashSet<>();
        List<Integer> legal = new ArrayList<>();
        boolean hasSource = working.checkpoint() != null;
        for (int index = 0; index < records.size(); index++) {
            HistoryStore.Record record = records.get(index);
            var item = record.item();
            hasSource |= item.message() != null;
            if (item.kind() == HistoryStore.Kind.TOOL_CALL && item.message() != null && item.message().toolCall() != null) {
                if (!working.isRecovered(record)) openCalls.add(callKey(record));
            } else if (item.kind() == HistoryStore.Kind.TOOL_RESULT || item.kind() == HistoryStore.Kind.RECOVERY_NOTICE) {
                openCalls.remove(callKey(record));
            }
            // Keep an assistant statement adjacent to its native calls as one continuous turn.
            boolean adjacentCall = index + 1 < records.size()
                    && records.get(index + 1).item().kind() == HistoryStore.Kind.TOOL_CALL
                    && item.kind() == HistoryStore.Kind.ASSISTANT_MESSAGE;
            if (hasSource && openCalls.isEmpty() && !adjacentCall) legal.add(index + 1);
        }
        // Keep the largest recent continuous tail that leaves a real summary budget.
        int summaryReserve = (int) Math.min(limits.summaryTokens(), target / 2);
        if (summaryReserve < 1) return null;
        long tailLimit = Math.min(limits.recentTokens(), target - summaryReserve);
        // Suffix estimates decrease as a complete group is removed; binary search avoids O(n²) rescans.
        int low = 0, high = legal.size() - 1, selected = -1;
        long selectedTokens = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int end = legal.get(middle);
            long tailTokens = estimate(working.tailMessages(end, records.size()), List.of());
            if (tailTokens <= tailLimit) {
                selected = middle; selectedTokens = tailTokens; high = middle - 1;
            } else low = middle + 1;
        }
        if (selected < 0) return null;
        int summaryBudget = (int) Math.min(limits.summaryTokens(), target - selectedTokens);
        return summaryBudget < 1 ? null : new Cut(legal.get(selected), selectedTokens, summaryBudget);
    }

    private Preparation prepared(WorkingContext working, List<ModelMessage> authority, long before, String failure) {
        return new Preparation(working.messages(authority), new Diagnostics(before,
                estimate(working.messages(), List.of()), failure));
    }
    private Preparation failure(WorkingContext working, List<ModelMessage> authority, List<ToolDefinition> tools,
                                long history, long hardInput, String code) {
        if (estimate(working.messages(authority), tools) > hardInput) throw new ContextSessionException(code);
        return prepared(working, authority, history, code);
    }
    private long estimate(List<ModelMessage> messages, List<ToolDefinition> tools) {
        long result = estimator.estimateInput(messages, tools);
        if (result < 0) throw new ContextSessionException("CONTEXT_BUDGET_EXCEEDED");
        return result;
    }
    private boolean valid(CompactionModel.Result result) {
        if (result == null || !result.completed() || result.summary() == null || result.summary().isBlank()) return false;
        try { return validator.test(result.summary()); } catch (RuntimeException failure) { return false; }
    }
    private static String callKey(HistoryStore.Record record) { return record.runId() + "\n" + record.item().callId(); }
    private static boolean isConflict(RuntimeException failure) {
        return "CHECKPOINT_REVISION_CONFLICT".equals(failure.getMessage())
                || (failure instanceof ContextSessionException sessionFailure && "CHECKPOINT_REVISION_CONFLICT".equals(sessionFailure.code()));
    }
    private String fingerprint(WorkingContext working, HistoryStore.Scope scope, List<ModelMessage> authority,
                               List<ToolDefinition> tools, Limits limits, String profile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(scope.toString(), Long.toString(working.revision()), Long.toString(working.lastSequence()),
                    working.checkpoint() == null ? "none" : working.checkpoint().id(), profile, estimator.version(), limits.toString())) {
                digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            for (ModelMessage message : authority) { digest.update(message.toString().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
            for (ToolDefinition tool : tools) { digest.update(tool.toString().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
