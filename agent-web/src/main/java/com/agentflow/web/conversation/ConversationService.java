package com.agentflow.web.conversation;

import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentSessionEntity;
import com.agentflow.web.run.RunCoordinator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Bounded owner-scoped queries over committed conversation facts. */
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 2)
public class ConversationService {
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED");
    private final EntityManager em;
    private final AgentSessionRepository sessions;
    private final ContextTextPolicy policy;
    private final RunCoordinator coordinator;
    private final ObjectMapper json = new ObjectMapper();

    public ConversationService(EntityManager em, AgentSessionRepository sessions, ContextTextPolicy policy, RunCoordinator coordinator) {
        this.em = Objects.requireNonNull(em);
        this.sessions = Objects.requireNonNull(sessions);
        this.policy = Objects.requireNonNull(policy);
        this.coordinator = Objects.requireNonNull(coordinator);
    }
    public record Conversation(String sessionId, String title, Instant createdAt, String lastTurnSequence) { }
    public record ConversationPage(List<Conversation> items, boolean hasMore, String nextBefore) { }
    public record Turn(String runId, String sessionId, String turnSequence, String status, String input,
                       String finalAnswer, String terminationReason, boolean recordingComplete,
                       boolean contentUnavailable, Instant createdAt, Instant finishedAt) { }
    public record TurnPage(List<Turn> items, boolean hasMore, String nextAfterSequence, String untilSequence) { }
    public ConversationPage list(String owner, String before, int limit) {
        validateLimit(limit);
        Cursor cursor = before == null ? null : decode(before);
        var query = em.createQuery("select s from AgentSessionEntity s where s.userId=:owner"
                + (cursor == null ? "" : " and (s.createdAt<:time or (s.createdAt=:time and s.id<:id))")
                + " order by s.createdAt desc, s.id desc", AgentSessionEntity.class).setParameter("owner", owner);
        if (cursor != null) query.setParameter("time", cursor.createdAt()).setParameter("id", cursor.id());
        var rows = query.setMaxResults(limit + 1).getResultList();
        boolean more = rows.size() > limit;
        var items = rows.subList(0, Math.min(rows.size(), limit)).stream().map(this::conversation).toList();
        String next = more ? encode(new Cursor(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).sessionId())) : null;
        return new ConversationPage(items, more, next);
    }

    public Conversation get(String owner, String session) { return conversation(owned(owner, session)); }

    public TurnPage turns(String owner, String session, String after, String until, int limit) {
        validateLimit(limit);
        owned(owner, session);
        if (coordinator.isStartupRecoveryBlocked()) throw new RunCoordinator.RunUnavailableException("SERVICE_RECOVERING", null);
        long lower = sequence(after);
        if (lower > 0 && until == null) throw new IllegalArgumentException("untilSequence required");
        Long maximum = em.createQuery("select max(t.turnSequence) from AgentTaskEntity t where t.sessionId=:session "
                + "and t.userId=:owner and t.status in :terminal", Long.class).setParameter("session", session)
                .setParameter("owner", owner).setParameter("terminal", TERMINAL).getSingleResult();
        long max = maximum == null ? 0 : maximum;
        long upper = until == null ? max : sequence(until);
        if (lower > upper || upper > max) throw new IllegalArgumentException("invalid sequence bounds");
        var rows = em.createQuery("select t.id as id, t.turnSequence as sequence, t.status as status, "
                + "t.terminationReason as reason, t.recordingComplete as recorded, t.createdAt as created, t.finishedAt as finished, "
                + "length(t.userInput) as inputLength, length(t.finalAnswer) as answerLength from AgentTaskEntity t "
                + "where t.sessionId=:session and t.userId=:owner and t.status in :terminal "
                + "and t.turnSequence>:after and t.turnSequence<=:until order by t.turnSequence", Tuple.class)
                .setParameter("session", session).setParameter("owner", owner).setParameter("terminal", TERMINAL)
                .setParameter("after", lower).setParameter("until", upper).setMaxResults(limit + 1).getResultList();
        var items = new ArrayList<Turn>();
        for (var row : rows.subList(0, Math.min(rows.size(), limit))) items.add(turn(owner, session, row));
        String next = items.isEmpty() ? Long.toString(lower) : items.get(items.size() - 1).turnSequence();
        return new TurnPage(List.copyOf(items), rows.size() > limit, next, Long.toString(upper));
    }

    private Turn turn(String owner, String session, Tuple row) {
        String status = row.get("status", String.class);
        boolean succeeded = "SUCCEEDED".equals(status);
        boolean unavailable = !fits(row.get("inputLength"), 8000) || (row.get("answerLength") != null && !fits(row.get("answerLength"), 65536));
        String input = null;
        String answer = null;
        if (!unavailable) {
            var body = em.createQuery("select t.userInput as input, " + (succeeded ? "t.finalAnswer" : "null")
                    + " as answer from AgentTaskEntity t where t.id=:id and t.sessionId=:session and t.userId=:owner "
                    + "and length(t.userInput)<=8000 and (t.finalAnswer is null or length(t.finalAnswer)<=65536)", Tuple.class)
                    .setParameter("id", row.get("id")).setParameter("session", session).setParameter("owner", owner).getResultList();
            if (body.size() != 1) unavailable = true;
            else {
                input = body.get(0).get("input", String.class);
                answer = body.get(0).get("answer", String.class);
                unavailable = input == null || input.length() > 8000 || (answer != null && answer.length() > 65536);
                if (!unavailable) {
                    input = policy.sanitizeHistory(input);
                    if (answer != null) answer = policy.sanitizeHistory(answer);
                    unavailable = input.length() > 8000 || (answer != null && answer.length() > 65536);
                }
            }
        }
        return new Turn(row.get("id", String.class), session, Long.toString(((Number) row.get("sequence")).longValue()),
                status, unavailable ? null : input, unavailable ? null : answer, row.get("reason", String.class),
                row.get("recorded", Boolean.class), unavailable, row.get("created", Instant.class), row.get("finished", Instant.class));
    }

    private AgentSessionEntity owned(String owner, String session) {
        validateSession(session);
        return sessions.findByIdAndUserId(session, owner).orElseThrow(RunCoordinator.RunNotFoundException::new);
    }

    private Conversation conversation(AgentSessionEntity entity) {
        String title = policy.sanitizeHistory(entity.getTitle());
        if (title.length() > 200) {
            int end = Character.isHighSurrogate(title.charAt(199)) ? 199 : 200;
            title = title.substring(0, end);
        }
        return new Conversation(entity.getId(), title, entity.getCreatedAt(), Long.toString(entity.getLastTurnSequence()));
    }

    private record Cursor(Instant createdAt, String id) { }
    private String encode(Cursor cursor) {
        var node = json.createObjectNode().put("createdAt", cursor.createdAt().toString()).put("id", cursor.id());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(node));
    }
    private Cursor decode(String value) {
        try {
            if (value.isEmpty() || value.length() > 512 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            var node = json.readTree(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
            if (!node.isObject() || node.size() != 2 || !node.has("createdAt") || !node.has("id")
                    || !node.get("createdAt").isTextual() || !node.get("id").isTextual()) throw new IllegalArgumentException();
            String id = node.get("id").asText();
            validateSession(id);
            return new Cursor(Instant.parse(node.get("createdAt").asText()), id);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid cursor", invalid); }
    }
    static long sequence(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("invalid sequence");
        return Long.parseLong(value);
    }
    static void validateSession(String value) {
        if (value == null || value.isBlank() || value.length() > 36) throw new IllegalArgumentException("invalid sessionId");
    }
    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("invalid limit");
    }
    private static boolean fits(Object value, int limit) { return value instanceof Number n && n.longValue() >= 0 && n.longValue() <= limit; }
}
