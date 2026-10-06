package com.agentflow.web.context;

import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.run.RunCoordinator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Public session views: bounded content only, without private adapter state. */
public class ContextHistoryService {
    private static final int PREVIEW_CHARS = 4096;
    private static final int PAYLOAD_CHARS = 65536;
    private static final int PAGE_BYTES = 256 * 1024;
    private final EntityManager manager;
    private final AgentSessionRepository sessions;
    private final ContextTextPolicy policy;
    private final RunCoordinator coordinator;
    private final Environment environment;
    private final ObjectMapper mapper;
    private final Clock clock;
    private volatile ModelCallLedger ledger;

    public ContextHistoryService(EntityManager manager, AgentSessionRepository sessions, ContextTextPolicy policy,
                                 RunCoordinator coordinator, Environment environment, ObjectMapper mapper, Clock clock) {
        this.manager = Objects.requireNonNull(manager);
        this.sessions = Objects.requireNonNull(sessions);
        this.policy = Objects.requireNonNull(policy);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.environment = Objects.requireNonNull(environment);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }
    public void configureLedger(ModelCallLedger ledger) { this.ledger = Objects.requireNonNull(ledger); }
    public record HistoryMessage(String sequence, String runId, String kind, String role, Instant occurredAt,
                                 String decisionId, String callId, String toolName, String status, String preview,
                                 String artifactId, boolean contentUnavailable, boolean truncated, boolean redacted) { }
    public record HistoryPage(List<HistoryMessage> items, String untilSequence, boolean hasMore, String nextAfterSequence) {
        public HistoryPage { items = List.copyOf(items); }
    }
    public record Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long reasoningTokens) { }
    public record CompactionDiagnostic(String requestKey, String status, String checkpointId, String coveredThroughSequence,
                                       long sourceHistoryTokens, Long retainedHistoryTokens, Long summaryTokens, String failureCode) { }
    public record ContextSnapshot(String sessionId, String model, int windowTokens, Long estimatedInputTokens,
                                  String checkpointId, String coveredThroughSequence, Long summaryTokenCount,
                                  boolean usageComplete, Usage actualUsage, Usage knownUsage,
                                  int outputReserveTokens, int safetyTokens, int compactionThresholdTokens,
                                  String estimateSource, CompactionDiagnostic lastCompaction, String historyState) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 3)
    public HistoryPage messages(String owner, String session, String after, String until, int limit) {
        long lower = cursor(after == null ? "0" : after);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (lower > 0 && until == null) throw new IllegalArgumentException("untilSequence is required after the first page");
        SessionContextEntity state = requireOwned(owner, session, false);
        checkAvailability(owner, session);
        long maximum = state == null ? 0 : state.getLastRecordSequence();
        long upper = until == null ? maximum : cursor(until);
        if (lower > upper || upper > maximum) throw new IllegalArgumentException("invalid history cursor range");
        List<Tuple> rows = manager.createQuery("select r.id as id, r.runId as runId, r.recordSequence as sequence, r.kind as kind, r.role as role, r.occurredAt as occurredAt, r.decisionId as decisionId, r.callId as callId, r.toolName as toolName, r.status as status, r.artifactId as artifactId, r.payloadBytes as bytes from HistoryRecordEntity r where r.sessionId=:session and r.recordSequence>:after and r.recordSequence<=:until order by r.recordSequence", Tuple.class)
                .setParameter("session", session).setParameter("after", lower).setParameter("until", upper)
                .setMaxResults(limit + 1).getResultList();
        List<HistoryMessage> messages = new ArrayList<>();
        for (Tuple row : rows) {
            if (messages.size() == limit) break;
            HistoryMessage item = preview(session, row);
            List<HistoryMessage> candidate = new ArrayList<>(messages);
            candidate.add(item);
            // Include a worst-case continuation cursor when enforcing the complete response envelope.
            if (mapper.writeValueAsBytes(new HistoryPage(candidate, Long.toString(upper), true,
                    Long.toString(Long.MAX_VALUE))).length > PAGE_BYTES) break;
            messages.add(item);
        }
        if (messages.isEmpty() && !rows.isEmpty()) throw new IllegalStateException("HISTORY_PAGE_TOO_LARGE");
        boolean more = rows.size() > messages.size();
        return new HistoryPage(messages, Long.toString(upper), more,
                more ? messages.get(messages.size() - 1).sequence() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 3)
    public ContextSnapshot context(String owner, String session) {
        SessionContextEntity state = requireOwned(owner, session, false);
        checkAvailability(owner, session);
        List<Tuple> checkpoints = state == null || state.getActiveCheckpointId() == null ? List.of()
                : manager.createQuery("select c.id as id, c.coveredThroughSequence as through, c.modelRequestKey as requestKey, c.sourceHistoryTokens as sourceTokens, c.retainedHistoryTokens as retainedTokens, c.summaryTokens as summaryTokens from ContextCheckpointEntity c where c.id=:id and c.sessionId=:session", Tuple.class)
                    .setParameter("id", state.getActiveCheckpointId()).setParameter("session", session).setMaxResults(1).getResultList();
        Tuple confirmed = checkpoints.isEmpty() ? null : checkpoints.get(0);
        int window = environment.getProperty("agentflow.harness.context.window-tokens", Integer.class, 272000);
        int output = environment.getProperty("agentflow.harness.context.output-tokens", Integer.class, 32768);
        int safety = environment.getProperty("agentflow.harness.context.safety-tokens", Integer.class, 8192);
        double ratio = environment.getProperty("agentflow.harness.context.compact-ratio", Double.class, 0.80);
        var diagnostic = confirmed == null ? null : new CompactionDiagnostic(confirmed.get("requestKey", String.class), "SUCCEEDED",
                confirmed.get("id", String.class), Long.toString(confirmed.get("through", Long.class)), confirmed.get("sourceTokens", Long.class),
                confirmed.get("retainedTokens", Long.class), confirmed.get("summaryTokens", Long.class), null);
        ModelCallLedger activeLedger = ledger;
        var accounting = activeLedger == null ? null : activeLedger.snapshot(session);
        if (accounting != null && accounting.lastCompaction() != null) diagnostic = accounting.lastCompaction();
        if (accounting != null && accounting.estimatedInputTokens() != null) {
            output = accounting.outputReserveTokens();
            safety = accounting.safetyTokens();
        }
        return new ContextSnapshot(session, environment.getProperty("agentflow.harness.model.name", "gpt-6.1-sol"),
                window, accounting == null ? null : accounting.estimatedInputTokens(), confirmed == null ? null : confirmed.get("id", String.class),
                confirmed == null ? "0" : Long.toString(confirmed.get("through", Long.class)),
                confirmed == null ? null : confirmed.get("summaryTokens", Long.class), accounting != null && accounting.usageComplete(),
                accounting == null ? null : accounting.actualUsage(),
                accounting == null ? new Usage(null, null, null, null) : accounting.knownUsage(), output, safety,
                (int) Math.min(window - output - safety, Math.floor(window * ratio)),
                accounting == null ? null : accounting.estimateSource(), diagnostic, "CONFIRMED");
    }

    @Transactional(timeout = 3)
    public void touch(String owner, String session) {
        SessionContextEntity state = requireOwned(owner, session, true);
        if (state == null) {
            state = new SessionContextEntity(session, clock.instant());
            manager.persist(state);
        } else state.touch(clock.instant());
        manager.flush();
    }

    private SessionContextEntity requireOwned(String owner, String session, boolean lock) {
        if (owner == null || owner.isBlank() || session == null || session.isBlank() || session.length() > 36)
            throw new IllegalArgumentException("invalid owner or sessionId");
        if ((lock ? sessions.findOwnedForUpdate(session, owner) : sessions.findByIdAndUserId(session, owner)).isEmpty())
            throw new RunCoordinator.RunNotFoundException();
        SessionContextEntity state = lock ? manager.find(SessionContextEntity.class, session, LockModeType.PESSIMISTIC_WRITE)
                : manager.find(SessionContextEntity.class, session);
        if (state != null && state.getDeletedAt() != null) throw new RunCoordinator.RunNotFoundException();
        return state;
    }

    private void checkAvailability(String owner, String session) {
        List<String> ids = manager.createQuery("select t.id from AgentTaskEntity t where t.sessionId=:session and t.userId=:owner and t.status in ('QUEUED','RUNNING')", String.class)
                .setParameter("session", session).setParameter("owner", owner).setMaxResults(257).getResultList();
        if (ids.size() > 256) throw new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE", session);
        for (String id : ids) coordinator.getOwned(owner, id);
    }

    private HistoryMessage preview(String session, Tuple row) {
        List<Tuple> bodies = manager.createQuery("select substring(r.inlinePayload, 1, :maximum) as payload, length(r.inlinePayload) as chars from HistoryRecordEntity r where r.id=:id and r.sessionId=:session", Tuple.class)
                .setParameter("maximum", PAYLOAD_CHARS).setParameter("id", row.get("id", String.class))
                .setParameter("session", session).setMaxResults(1).getResultList();
        String text = "";
        String toolName = row.get("toolName", String.class);
        boolean unavailable = false;
        boolean truncated = false;
        boolean redacted = false;
        if (bodies.isEmpty()) unavailable = true;
        else {
            Tuple body = bodies.get(0);
            String payload = body.get("payload", String.class);
            Number chars = (Number) body.get("chars");
            if (payload != null) {
                if (chars.longValue() > PAYLOAD_CHARS) { unavailable = true; truncated = true; }
                else {
                    try {
                        var parsed = mapper.readTree(payload);
                        text = parsed.path("content").asString("");
                        if (parsed.hasNonNull("toolName")) toolName = parsed.path("toolName").asString();
                        if (parsed.hasNonNull("arguments")) text = mapper.writeValueAsString(parsed.path("arguments"));
                        String sanitized = policy.sanitizeInput(text);
                        redacted = !sanitized.equals(text);
                        truncated = sanitized.length() > PREVIEW_CHARS;
                        text = sanitized.substring(0, Math.min(PREVIEW_CHARS, sanitized.length()));
                    } catch (RuntimeException invalid) { text = ""; unavailable = true; }
                }
            }
        }
        return new HistoryMessage(Long.toString(row.get("sequence", Long.class)), row.get("runId", String.class),
                row.get("kind", String.class), row.get("role", String.class), row.get("occurredAt", Instant.class),
                row.get("decisionId", String.class), row.get("callId", String.class), toolName, row.get("status", String.class),
                text, row.get("artifactId", String.class), unavailable, truncated, redacted);
    }

    private static long cursor(String value) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("invalid history cursor");
        try { return Long.parseLong(value); }
        catch (NumberFormatException overflow) { throw new IllegalArgumentException("invalid history cursor", overflow); }
    }
}
