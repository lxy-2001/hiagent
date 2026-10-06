package com.agentflow.web.context;

import com.agentflow.core.context.ContextSessionException;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.model.ModelMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Confirms UNKNOWN notices only; never invokes or reconstructs historical executable operations. */
public final class OrphanCallRecovery {
    private static final String UNKNOWN = "UNKNOWN: no confirmed tool result was saved. The operation may have executed; do not automatically replay it.";
    private final JpaHistoryStore history;
    private final HistoryStore reliable;
    private final BoundedPersistenceRetry retry;
    private final Clock clock;
    public OrphanCallRecovery(JpaHistoryStore history, BoundedPersistenceRetry retry, Clock clock) {
        this.history = Objects.requireNonNull(history); this.retry = Objects.requireNonNull(retry);
        this.reliable = retry.history(history); this.clock = Objects.requireNonNull(clock);
    }

    /** New Run is already admitted; older runs are immutable terminal facts. Scan only fixed, scalar pages. */
    public int recover(HistoryStore.Scope scope, HistoryStore.Io io) {
        var state = reliable.state(scope, io);
        long until = state.lastSequence();
        long after = state.checkpoint() == null ? 0 : state.checkpoint().coveredThrough();
        int recovered = 0;
        while (after < until) {
            long cursor = after;
            var calls = retry.execute(io, "HISTORY_PERSISTENCE_FAILED", () -> history.unconfirmedCalls(scope, cursor, until, 128, io));
            if (calls.isEmpty()) break;
            for (var call : calls) {
                if (call.callId() == null || call.callId().isBlank() || call.toolName() == null || call.toolName().isBlank()) {
                    throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED", new IllegalStateException("HISTORY_CALL_INVALID"));
                }
                var message = new ModelMessage("tool", UNKNOWN, call.toolName(), call.callId(), null);
                var items = List.of(new HistoryStore.Item(HistoryStore.Kind.RECOVERY_NOTICE, message, clock.instant(),
                        call.decisionId(), call.callId(), "UNKNOWN", null, null));
                // The stable key and original body are captured once, so confirmation loss cannot duplicate recovery.
                var oldScope = new HistoryStore.Scope(scope.userId(), scope.sessionId(), call.runId());
                reliable.appendBatch(oldScope, mutationId(call.runId(), call.callId()), history.payloadHash(items), items, io);
                recovered = Math.addExact(recovered, 1);
            }
            long next = calls.get(calls.size() - 1).sequence();
            if (next <= after) throw new ContextSessionException("HISTORY_PERSISTENCE_FAILED");
            after = next;
        }
        return recovered;
    }
    private static String mutationId(String run, String call) {
        try { return "recovery:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((run + ":" + call).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
