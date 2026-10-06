package com.agentflow.web.artifact;

import com.agentflow.core.context.HistoryStore;
import com.agentflow.web.context.BoundedPersistenceRetry;
import com.agentflow.web.run.RunCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit deletion is resumable; files are released outside short database transactions. */
public final class ArtifactMaintenance {
    private static final Logger LOG = LoggerFactory.getLogger(ArtifactMaintenance.class);
    private static final int ROWS_PER_TABLE = 32;
    private static final String ACTIVE = "status not in ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED')";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final LocalArtifactStore artifacts;
    private final Clock clock;
    private final BoundedPersistenceRetry retry;
    private final AtomicBoolean maintaining = new AtomicBoolean();
    public ArtifactMaintenance(JdbcTemplate jdbc, PlatformTransactionManager manager, LocalArtifactStore artifacts, Clock clock) {
        this(jdbc, manager, artifacts, clock, new BoundedPersistenceRetry(clock));
    }
    public ArtifactMaintenance(JdbcTemplate jdbc, PlatformTransactionManager manager, LocalArtifactStore artifacts, Clock clock, BoundedPersistenceRetry retry) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(3);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.clock = Objects.requireNonNull(clock);
        this.retry = Objects.requireNonNull(retry);
    }
    public record SessionDeletion(String sessionId, String status) { }

    public SessionDeletion delete(String owner, String session) {
        if (session == null || !session.matches("[A-Za-z0-9_-]{1,36}")) throw new IllegalArgumentException("invalid session");
        return retry.execute(io(), "PERSISTENCE_UNAVAILABLE", () -> transaction.execute(tx -> {
            // Admission uses the same session -> context lock order.
            var owned = jdbc.queryForList("select id from agent_session where id=? and user_id=? for update", String.class, session, owner);
            if (owned.isEmpty()) throw new RunCoordinator.RunNotFoundException();
            var context = jdbc.queryForList("select deleted_at from agent_session_context where session_id=? for update", session);
            if (!context.isEmpty() && context.get(0).get("deleted_at") != null) return new SessionDeletion(session, "CLEANUP_PENDING");
            if (jdbc.queryForObject("select count(*) from agent_task where session_id=? and " + ACTIVE, Long.class, session) > 0) {
                throw new RunCoordinator.SessionBusyException();
            }
            var now = clock.instant();
            if (context.isEmpty()) {
                jdbc.update("insert into agent_session_context(session_id,last_record_sequence,context_revision,last_used_at,deleted_at) values(?,0,0,?,?)", session, now, now);
            } else {
                jdbc.update("update agent_session_context set deleted_at=? where session_id=?", now, session);
            }
            return new SessionDeletion(session, "CLEANUP_PENDING");
        }));
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void maintainDeletedSessions() {
        if (!maintaining.compareAndSet(false, true)) return;
        try {
            artifacts.trimToConfiguredQuota(32,io());
            var sessions = jdbc.queryForList("select session_id from agent_session_context where deleted_at is not null order by deleted_at,session_id limit 4", String.class);
            for (String session : sessions) {
                try {
                    var io = io();
                    if (!retry.execute(io, "ARTIFACT_STORAGE_FAILED", () -> artifacts.deleteSession(session, ROWS_PER_TABLE, io))) continue;
                    boolean removed = retry.execute(io, "PERSISTENCE_UNAVAILABLE", () -> transaction.execute(tx -> cleanupRows(session)));
                    if (removed) artifacts.forgetSession(session);
                } catch (RuntimeException failure) {
                    // The marker remains durable; the next bounded pass resumes storage only.
                    LOG.warn("Session cleanup remains pending; storage could not be confirmed.");
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("Session cleanup scan could not be confirmed.");
        } finally {
            maintaining.set(false);
        }
    }

    private boolean cleanupRows(String session) {
        if (jdbc.queryForList("select id from agent_session where id=? for update", String.class, session).isEmpty()) return true;
        var marker = jdbc.queryForList("select deleted_at from agent_session_context where session_id=? for update", session);
        if (marker.isEmpty() || marker.get(0).get("deleted_at") == null) return false;
        if (jdbc.queryForObject("select count(*) from agent_task where session_id=? and " + ACTIVE, Long.class, session) > 0) return false;
        jdbc.update("update agent_session_context set active_checkpoint_id=null where session_id=?", session);
        boolean empty = true;
        for (String table : List.of("agent_history_record", "agent_history_batch", "agent_context_model_call", "agent_responses_continuation", "agent_artifact", "agent_confirmed_memory", "agent_memory")) {
            empty &= deleteBatch(table, "session_id=?", session, "id");
        }
        // A checkpoint can reference an earlier one; remove descendants before ancestors.
        empty &= deleteBatch("agent_context_checkpoint", "session_id=?", session, "revision desc");
        empty &= deleteBatch("agent_step", "task_id in (select id from agent_task where session_id=?)", session, "id");
        empty &= deleteBatch("agent_tool_invocation", "task_id in (select id from agent_task where session_id=?)", session, "id");
        if (!empty) return false;
        if (!deleteBatch("agent_task", "session_id=?", session, "id")) return false;
        jdbc.update("delete from agent_session_context where session_id=?", session);
        jdbc.update("delete from agent_session where id=?", session);
        return true;
    }

    private boolean deleteBatch(String table, String predicate, String session, String order) {
        // All identifiers are fixed internal literals; values are always parameters.
        var ids = jdbc.queryForList("select id from " + table + " where " + predicate + " order by " + order + " limit " + ROWS_PER_TABLE, String.class, session);
        for (String id : ids) jdbc.update("delete from " + table + " where id=?", id);
        return jdbc.queryForObject("select count(*) from " + table + " where " + predicate, Long.class, session) == 0;
    }
    private HistoryStore.Io io() {
        return new HistoryStore.Io(() -> Thread.currentThread().isInterrupted(), clock.instant().plusSeconds(10));
    }
}
