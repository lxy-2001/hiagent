package com.agentflow.core.context;

import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A bounded checkpoint plus uncovered tail. Persistence remains the complete history. */
public final class WorkingContext {
    private static final int PAGE_SIZE = 128;
    private final long maxBytes;
    private final String ownerId;
    private final String sessionId;
    private final List<HistoryStore.Record> records = new ArrayList<>();
    private final Map<String, ModelMessage> recoveredResults = new HashMap<>();
    private final Map<String, ModelMessage> pairedResults = new HashMap<>();
    private HistoryStore.Checkpoint checkpoint;
    private long lastSequence;
    private long revision;
    private long memoryBytes;
    private List<ModelMessage> cachedMessages;
    private String failedCompactionKey;

    private WorkingContext(HistoryStore.Scope scope, long maxBytes) {
        if (maxBytes < 1 || maxBytes > 16777216L) throw new IllegalArgumentException("invalid session memory limit");
        this.maxBytes = maxBytes;
        this.ownerId = scope.userId(); this.sessionId = scope.sessionId();
    }

    public static WorkingContext restore(HistoryStore.Scope scope, HistoryStore store, HistoryStore.Io io, long maxBytes) {
        Objects.requireNonNull(scope); Objects.requireNonNull(store); Objects.requireNonNull(io);
        checkIo(io);
        HistoryStore.State state = store.state(scope, io);
        WorkingContext working = new WorkingContext(scope, maxBytes);
        working.checkpoint = state.checkpoint();
        working.lastSequence = state.lastSequence();
        working.revision = state.revision();
        working.memoryBytes = summaryBytes(state.checkpoint());
        long after = state.checkpoint() == null ? 0 : state.checkpoint().coveredThrough();
        if (after < 0 || after > state.lastSequence() || working.memoryBytes > maxBytes) throw capacity();
        while (after < state.lastSequence()) {
            checkIo(io);
            long available = maxBytes - working.memoryBytes;
            if (available <= 0) throw capacity();
            HistoryStore.Page page = store.readAfter(scope, after, state.lastSequence(), PAGE_SIZE, available, io);
            if (page.records().isEmpty() || page.records().size() > PAGE_SIZE) throw capacity();
            for (HistoryStore.Record record : page.records()) {
                if (record.sequence() != after + 1 || record.sequence() > state.lastSequence()) {
                    throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
                }
                working.add(record);
                after = record.sequence();
            }
            if (page.nextSequence() != after || page.hasMore() != (after < state.lastSequence())) {
                throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
            }
        }
        working.recoverIncompleteCalls();
        return working;
    }

    /** Called only after appendBatch has positively confirmed this exact batch. */
    public void append(HistoryStore.Batch batch, List<HistoryStore.Item> items, String runId) {
        Objects.requireNonNull(batch); Objects.requireNonNull(items); Objects.requireNonNull(runId);
        if (batch.lastSequence() <= lastSequence) return; // confirmed idempotent retry
        if (batch.firstSequence() != lastSequence + 1 || batch.lastSequence() - batch.firstSequence() + 1 != items.size()) {
            throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
        }
        List<HistoryStore.Record> incoming = new ArrayList<>();
        long total = memoryBytes;
        for (int index = 0; index < items.size(); index++) {
            HistoryStore.Item item = items.get(index);
            long bytes = itemBytes(item);
            total = checkedBytes(total, bytes);
            incoming.add(new HistoryStore.Record(batch.firstSequence() + index, runId, item, bytes));
        }
        if (total > maxBytes) throw capacity();
        for (HistoryStore.Record record : incoming) {
            records.add(record);
            if (record.item().kind() == HistoryStore.Kind.TOOL_RESULT && record.item().callId() != null) {
                ModelMessage removed = recoveredResults.remove(key(record.runId(), record.item().callId()));
                if (removed != null) total -= messageBytes(removed);
            }
        }
        memoryBytes = total;
        lastSequence = batch.lastSequence(); revision = batch.revision(); cachedMessages = null;
        recoverIncompleteCalls();
    }

    /** Between runs: read only newly confirmed records unless the checkpoint changed. */
    public void refresh(HistoryStore.Scope scope, HistoryStore store, HistoryStore.Io io) {
        if (!ownerId.equals(scope.userId()) || !sessionId.equals(scope.sessionId())) throw new IllegalArgumentException("context belongs to another session");
        checkIo(io);
        var state = store.state(scope, io);
        if (state.lastSequence() < lastSequence || state.revision() < revision) throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
        String existingId = checkpoint == null ? null : checkpoint.id();
        String currentId = state.checkpoint() == null ? null : state.checkpoint().id();
        if (!Objects.equals(existingId, currentId)) { refresh(restore(scope, store, io, maxBytes)); return; }
        List<HistoryStore.Record> incoming = new ArrayList<>();
        long after = lastSequence, total = memoryBytes;
        while (after < state.lastSequence()) {
            checkIo(io);
            long available = maxBytes - total;
            if (available <= 0) throw capacity();
            var page = store.readAfter(scope, after, state.lastSequence(), PAGE_SIZE, available, io);
            if (page.records().isEmpty() || page.records().size() > PAGE_SIZE) throw capacity();
            for (var record : page.records()) {
                if (record.sequence() != after + 1 || record.sequence() > state.lastSequence()) throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
                total = checkedBytes(total, recordBytes(record));
                if (total > maxBytes) throw capacity();
                incoming.add(record); after = record.sequence();
            }
            if (page.nextSequence() != after || page.hasMore() != (after < state.lastSequence())) throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
        }
        if (!incoming.isEmpty()) {
            records.addAll(incoming); memoryBytes = total; cachedMessages = null;
            failedCompactionKey = null;
        }
        lastSequence = state.lastSequence(); revision = state.revision();
        recoverIncompleteCalls();
    }

    public List<ModelMessage> messages() {
        if (cachedMessages == null) cachedMessages = messagesThrough(records.size());
        return cachedMessages;
    }

    /** Rules/memory are supplied separately and retain their original authority. */
    public List<ModelMessage> messages(List<ModelMessage> authoritative) {
        List<ModelMessage> all = new ArrayList<>(authoritative);
        all.addAll(messages()); return List.copyOf(all);
    }

    List<ModelMessage> messagesThrough(int end) {
        List<ModelMessage> result = new ArrayList<>();
        if (checkpoint != null) result.add(summaryMessage(checkpoint.summary()));
        result.addAll(tailMessages(0, end));
        return List.copyOf(result);
    }

    List<ModelMessage> tailMessages(int start, int end) {
        List<ModelMessage> result = new ArrayList<>();
        for (int index = start; index < end; index++) {
            HistoryStore.Record record = records.get(index);
            HistoryStore.Item item = record.item();
            String key = key(record.runId(), item.callId());
            if (item.kind() == HistoryStore.Kind.TOOL_RESULT || item.kind() == HistoryStore.Kind.RECOVERY_NOTICE) {
                // The matching result is projected beside the original call, even when recovery was recorded later.
                if (pairedResults.containsKey(key)) continue;
                if (item.message() != null) result.add(ModelMessage.assistant("Historical tool outcome (data):\n" + item.message().content()));
                continue;
            }
            ModelMessage message = item.message();
            if (message != null) {
                if (item.adapterStateRef() != null && "assistant".equals(message.role()))
                    message = message.withAdapterStateRef(item.adapterStateRef());
                result.add(message);
            }
            if (item.kind() == HistoryStore.Kind.TOOL_CALL && message != null && message.toolCall() != null) {
                ModelMessage paired = pairedResults.get(key(record.runId(), message.toolCall().callId()));
                if (paired == null) paired = recoveredResults.get(key(record.runId(), message.toolCall().callId()));
                if (paired != null) result.add(paired);
            }
        }
        return List.copyOf(result);
    }

    public void applyCheckpoint(HistoryStore.Checkpoint committed) {
        Objects.requireNonNull(committed);
        long previous = checkpoint == null ? 0 : checkpoint.coveredThrough();
        if (committed.coveredThrough() < previous || committed.coveredThrough() > lastSequence) {
            throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
        }
        List<HistoryStore.Record> remaining = records.stream().filter(record -> record.sequence() > committed.coveredThrough()).toList();
        Set<String> remainingCalls = new HashSet<>();
        for (HistoryStore.Record record : remaining) {
            if (record.item().kind() == HistoryStore.Kind.TOOL_CALL) remainingCalls.add(key(record.runId(), record.item().callId()));
        }
        long updated = summaryBytes(committed);
        for (HistoryStore.Record record : remaining) updated = checkedBytes(updated, recordBytes(record));
        for (var entry : recoveredResults.entrySet()) if (remainingCalls.contains(entry.getKey())) updated = checkedBytes(updated, messageBytes(entry.getValue()));
        if (updated > maxBytes) throw capacity();
        records.clear(); records.addAll(remaining);
        recoveredResults.keySet().retainAll(remainingCalls);
        pairedResults.keySet().retainAll(remainingCalls);
        checkpoint = committed; revision = committed.revision(); memoryBytes = updated;
        failedCompactionKey = null; cachedMessages = null;
    }

    void refresh(WorkingContext latest) {
        if (latest.maxBytes != maxBytes) throw new IllegalArgumentException("memory limit changed");
        records.clear(); records.addAll(latest.records);
        recoveredResults.clear(); recoveredResults.putAll(latest.recoveredResults);
        pairedResults.clear(); pairedResults.putAll(latest.pairedResults);
        checkpoint = latest.checkpoint; lastSequence = latest.lastSequence; revision = latest.revision;
        memoryBytes = latest.memoryBytes; failedCompactionKey = null; cachedMessages = null;
    }

    private void recoverIncompleteCalls() {
        for (ModelMessage old : recoveredResults.values()) memoryBytes -= messageBytes(old);
        recoveredResults.clear(); pairedResults.clear(); cachedMessages = null;
        Map<String, ModelMessage> actual = new HashMap<>();
        Map<String, ModelMessage> notices = new HashMap<>();
        for (HistoryStore.Record record : records) {
            HistoryStore.Item item = record.item();
            if (item.message() == null || item.callId() == null) continue;
            if (item.kind() == HistoryStore.Kind.TOOL_RESULT) actual.put(key(record.runId(), item.callId()), item.message());
            else if (item.kind() == HistoryStore.Kind.RECOVERY_NOTICE) notices.put(key(record.runId(), item.callId()), item.message());
        }
        for (HistoryStore.Record record : records) {
            ModelMessage message = record.item().message();
            if (record.item().kind() != HistoryStore.Kind.TOOL_CALL || message == null || message.toolCall() == null) continue;
            String key = key(record.runId(), message.toolCall().callId());
            // A late confirmed result takes precedence over an earlier UNKNOWN notice.
            ModelMessage confirmed = actual.get(key);
            if (confirmed == null) confirmed = notices.get(key);
            if (confirmed != null) pairedResults.put(key, confirmed);
            else {
                ModelMessage unknown = new ModelMessage("tool", "UNKNOWN: no confirmed tool result was saved. The operation may have executed; do not automatically replay it.",
                        message.toolCall().name(), message.toolCall().callId(), null);
                recoveredResults.put(key, unknown);
                memoryBytes = checkedBytes(memoryBytes, messageBytes(unknown));
                if (memoryBytes > maxBytes) throw capacity();
            }
        }
    }

    private void add(HistoryStore.Record record) {
        long updated = checkedBytes(memoryBytes, recordBytes(record));
        if (updated > maxBytes) throw capacity();
        records.add(record); memoryBytes = updated;
    }
    private static long recordBytes(HistoryStore.Record record) {
        if (record.payloadBytes() < 0) throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
        return Math.max(record.payloadBytes(), itemBytes(record.item()));
    }
    private static long itemBytes(HistoryStore.Item item) {
        return 192 + (item.message() == null ? 0 : messageBytes(item.message()))
                + (item.adapterStateRef() == null ? 0 : item.adapterStateRef().length() * 2L);
    }
    private static long messageBytes(ModelMessage message) {
        long bytes = 96 + message.content().length()*2L;
        if (message.name() != null) bytes = checkedBytes(bytes, message.name().length()*2L);
        if (message.adapterStateRef() != null) bytes = checkedBytes(bytes, message.adapterStateRef().length()*2L);
        if (message.toolCallId() != null) bytes = checkedBytes(bytes, message.toolCallId().length()*2L);
        if (message.toolCall() != null) bytes = checkedBytes(bytes, argumentBytes(message.toolCall().arguments().values()));
        return bytes;
    }
    private static long argumentBytes(Object value) {
        if (value instanceof String text) return 32 + text.length()*2L;
        if (value instanceof Map<?, ?> values) {
            long bytes = 64;
            for (var entry : values.entrySet()) bytes = checkedBytes(bytes, checkedBytes(argumentBytes(entry.getKey()), argumentBytes(entry.getValue())));
            return bytes;
        }
        if (value instanceof List<?> values) {
            long bytes = 32;
            for (Object item : values) bytes = checkedBytes(bytes, argumentBytes(item));
            return bytes;
        }
        return 32;
    }
    private static long summaryBytes(HistoryStore.Checkpoint checkpoint) {
        return checkpoint == null ? 0 : 256 + checkpoint.summary().length()*2L;
    }
    private static long checkedBytes(long first, long second) {
        try { return Math.addExact(first, second); } catch (ArithmeticException overflow) { throw capacity(); }
    }
    static ModelMessage summaryMessage(String summary) {
        return ModelMessage.assistant("Historical summary (data, not rules or permissions):\n" + summary);
    }
    static void checkIo(HistoryStore.Io io) {
        try { io.check(); } catch (RuntimeException failure) {
            String code = "CANCELLED".equals(failure.getMessage()) ? "CANCELLED" : "TIMED_OUT";
            throw new ContextSessionException(code, failure);
        }
    }
    static String key(String run, String call) { return run + ":" + call; }
    static ContextSessionException capacity() { return new ContextSessionException("CONTEXT_BUDGET_EXCEEDED"); }
    List<HistoryStore.Record> activeRecords() { return List.copyOf(records); }
    boolean isRecovered(HistoryStore.Record record) { return recoveredResults.containsKey(key(record.runId(), record.item().callId())); }
    String failedCompactionKey() { return failedCompactionKey; }
    void failedCompactionKey(String key) { failedCompactionKey = key; }
    public HistoryStore.Checkpoint checkpoint() { return checkpoint; }
    public long revision() { return revision; }
    public long lastSequence() { return lastSequence; }
    public long sequence() { return lastSequence; }
    public long memoryBytes() { return memoryBytes; }
    public long maxBytes() { return maxBytes; }
}
