package com.agentflow.web.context;

import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.llm.OpenAiResponsesAgentModelClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Request-key accounting; actual provider counters never contain estimated capacity tokens. */
public final class ModelCallLedger {
    private static final String TABLE = "agent_context_model_call";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BoundedPersistenceRetry retry;
    private final String estimateSource;
    private final String modelName;

    public record Snapshot(Long estimatedInputTokens, String estimateSource,
                           Integer outputReserveTokens, Integer safetyTokens,
                           boolean usageComplete, ContextHistoryService.Usage actualUsage,
                           ContextHistoryService.Usage knownUsage,
                           ContextHistoryService.CompactionDiagnostic lastCompaction) { }

    public ModelCallLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                           BoundedPersistenceRetry retry, TokenEstimator estimator, String modelName) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transaction.setTimeout(3);
        this.retry = Objects.requireNonNull(retry);
        this.estimateSource = text(Objects.requireNonNull(estimator).version(), 100);
        this.modelName = text(modelName, 100);
    }

    public void record(OpenAiResponsesAgentModelClient.CallReport report, HistoryStore.Io io) {
        Objects.requireNonNull(report);
        validate(report.scope(), report.requestKey(), report.purpose());
        text(report.model(), 100); text(report.status(), 32); optionalText(report.failureCode(), 128);
        Objects.requireNonNull(report.startedAt()); Objects.requireNonNull(report.finishedAt());
        var counters = report.counters() == null
                ? new OpenAiResponsesAgentModelClient.Counters(null, null, null, null) : report.counters();
        nonnegative(counters.inputTokens()); nonnegative(counters.outputTokens());
        nonnegative(counters.cachedInputTokens()); nonnegative(counters.reasoningTokens());
        String id = java.util.UUID.randomUUID().toString();
        retry.execute(io, "HISTORY_PERSISTENCE_FAILED", () -> transaction.execute(tx -> {
            io.check(); lockOwned(report.scope());
            ensureRow(report.scope(), report.requestKey(), report.purpose(), report.model(), id, report.startedAt());
            var rows = jdbc.query("select purpose,model,status,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,finished_at from "
                    + TABLE + " where run_id=? and request_key=?", (r, n) -> new Reported(r.getString("purpose"), r.getString("model"), r.getString("status"),
                    number(r,"input_tokens"),number(r,"output_tokens"),number(r,"cached_input_tokens"),number(r,"reasoning_tokens"),
                    r.getTimestamp("finished_at")), report.scope().runId(), report.requestKey());
            Reported saved = rows.get(0);
            if (!saved.purpose.equals(report.purpose()) || !saved.model.equals(report.model())
                    || saved.finishedAt != null && (!saved.status.equals(report.status()) || !Objects.equals(saved.input, counters.inputTokens())
                    || !Objects.equals(saved.output, counters.outputTokens()) || !Objects.equals(saved.cached, counters.cachedInputTokens())
                    || !Objects.equals(saved.reasoning, counters.reasoningTokens()))) {
                throw new IllegalStateException("MODEL_CALL_CONFLICT");
            }
            // After an unknown commit the same request is confirmed, never counted twice.
            if (saved.finishedAt == null) {
                jdbc.update("update " + TABLE + " set status=?,input_tokens=?,output_tokens=?,cached_input_tokens=?,reasoning_tokens=?,"
                        + "usage_source=?,failure_code=coalesce(failure_code,?),started_at=?,finished_at=? where run_id=? and request_key=?",
                        report.status(), counters.inputTokens(), counters.outputTokens(), counters.cachedInputTokens(), counters.reasoningTokens(),
                        counters.inputTokens() == null && counters.outputTokens() == null ? "UNAVAILABLE" : "PROVIDER",
                        report.failureCode(), time(report.startedAt()), time(report.finishedAt()), report.scope().runId(), report.requestKey());
            }
            io.check(); return null;
        }));
    }

    public void observe(ContextSessionService.Observation observation, HistoryStore.Io io) {
        Objects.requireNonNull(observation);
        validate(observation.scope(), observation.requestKey(), observation.purpose());
        nonnegative(observation.estimatedInputTokens()); nonnegative((long)observation.outputReserveTokens());
        nonnegative((long)observation.safetyTokens()); nonnegative(observation.sourceHistoryTokens());
        nonnegative(observation.retainedHistoryTokens()); optionalText(observation.failureCode(),128);
        String id = java.util.UUID.randomUUID().toString(); Instant observedAt = Instant.now();
        retry.execute(io, "HISTORY_PERSISTENCE_FAILED", () -> transaction.execute(tx -> {
            io.check(); lockOwned(observation.scope());
            ensureRow(observation.scope(), observation.requestKey(), observation.purpose(), modelName, id, observedAt);
            String purpose = jdbc.queryForObject("select purpose from " + TABLE + " where run_id=? and request_key=?",
                    String.class, observation.scope().runId(), observation.requestKey());
            if (!observation.purpose().equals(purpose)) throw new IllegalStateException("MODEL_CALL_CONFLICT");
            jdbc.update("update " + TABLE + " set estimated_input_tokens=?,output_reserve_tokens=?,safety_tokens=?,estimate_source=?,"
                    + "source_history_tokens=coalesce(?,source_history_tokens),retained_history_tokens=coalesce(?,retained_history_tokens),"
                    + "failure_code=coalesce(?,failure_code) where run_id=? and request_key=?",
                    observation.estimatedInputTokens(),observation.outputReserveTokens(),observation.safetyTokens(),estimateSource,
                    observation.sourceHistoryTokens(),observation.retainedHistoryTokens(),observation.failureCode(),
                    observation.scope().runId(),observation.requestKey());
            io.check(); return null;
        }));
    }

    /** Scalar bounded diagnostics; caller has already checked session ownership. */
    public Snapshot snapshot(String sessionId) {
        text(sessionId,36);
        var totals = jdbc.query("select count(*) calls,count(input_tokens) inputs,count(output_tokens) outputs,"
                + "count(cached_input_tokens) caches,count(reasoning_tokens) reasons,sum(input_tokens) input_sum,sum(output_tokens) output_sum,"
                + "sum(cached_input_tokens) cache_sum,sum(reasoning_tokens) reason_sum from " + TABLE + " where session_id=?", (r,n) -> {
            long calls = r.getLong("calls");
            var known = new ContextHistoryService.Usage(number(r,"input_sum"),number(r,"output_sum"),number(r,"cache_sum"),number(r,"reason_sum"));
            boolean complete = calls > 0 && calls == r.getLong("inputs") && calls == r.getLong("outputs");
            var actual = !complete ? null : new ContextHistoryService.Usage(known.inputTokens(),known.outputTokens(),
                    calls == r.getLong("caches") ? known.cachedInputTokens() : null,
                    calls == r.getLong("reasons") ? known.reasoningTokens() : null);
            return new Aggregate(complete,actual,known);
        }, sessionId).get(0);
        var estimates = jdbc.query("select estimated_input_tokens,estimate_source,output_reserve_tokens,safety_tokens from " + TABLE
                + " where session_id=? and estimate_source<>'UNAVAILABLE' order by started_at desc,id desc fetch first 1 row only",
                (r,n) -> new Estimate(r.getLong(1),r.getString(2),r.getInt(3),r.getInt(4)),sessionId);
        var compactions = jdbc.query("select c.request_key,c.failure_code,c.status,c.source_history_tokens,c.retained_history_tokens,"
                + "p.id checkpoint_id,p.covered_through_sequence,p.summary_tokens,p.source_history_tokens checkpoint_source,"
                + "p.retained_history_tokens checkpoint_retained from " + TABLE + " c left join agent_context_checkpoint p"
                + " on p.session_id=c.session_id and p.model_request_key=c.request_key where c.session_id=? and c.purpose='COMPACTION'"
                + " and (c.source_history_tokens is not null or c.failure_code is not null or p.id is not null)"
                + " order by c.started_at desc,c.id desc fetch first 1 row only", (r,n) -> {
            String checkpoint = r.getString("checkpoint_id"); String failure = r.getString("failure_code");
            boolean committed = checkpoint != null && failure == null;
            Long source = number(r,"source_history_tokens"); if (source == null) source = number(r,"checkpoint_source");
            Long retained = number(r,"retained_history_tokens"); if (retained == null) retained = number(r,"checkpoint_retained");
            Long through = number(r,"covered_through_sequence");
            return new ContextHistoryService.CompactionDiagnostic(r.getString("request_key"),committed ? "SUCCEEDED" : "FAILED",
                    committed ? checkpoint : null,committed && through != null ? Long.toString(through) : null,
                    source == null ? 0 : source,retained,committed ? number(r,"summary_tokens") : null,
                    committed ? null : failure == null ? "CHECKPOINT_UNCONFIRMED" : failure);
        },sessionId);
        Estimate estimate = estimates.isEmpty() ? null : estimates.get(0);
        return new Snapshot(estimate == null ? null : estimate.tokens, estimate == null ? null : estimate.source,
                estimate == null ? null : estimate.outputReserveTokens, estimate == null ? null : estimate.safetyTokens,
                totals.complete,totals.actual,totals.known,compactions.isEmpty() ? null : compactions.get(0));
    }

    private void ensureRow(HistoryStore.Scope scope, String key, String purpose, String model, String id, Instant at) {
        List<String> rows = jdbc.query("select session_id from " + TABLE + " where run_id=? and request_key=?",
                (r,n) -> r.getString(1),scope.runId(),key);
        if (!rows.isEmpty()) {
            if (!scope.sessionId().equals(rows.get(0))) throw new IllegalStateException("MODEL_CALL_CONFLICT");
            return;
        }
        jdbc.update("insert into " + TABLE + " (id,session_id,run_id,request_key,purpose,status,provider,model,usage_source,"
                + "estimated_input_tokens,output_reserve_tokens,safety_tokens,estimate_source,started_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,scope.sessionId(),scope.runId(),key,purpose,"REQUESTED","openai-responses",model,"UNAVAILABLE",0L,0L,0L,"UNAVAILABLE",time(at));
    }

    private void lockOwned(HistoryStore.Scope scope) {
        List<String> owners = jdbc.query("select user_id from agent_session where id=? for update", (r,n)->r.getString(1),scope.sessionId());
        if (owners.isEmpty() || !scope.userId().equals(owners.get(0))) throw new IllegalStateException("NOT_FOUND");
        var contexts = jdbc.query("select deleted_at from agent_session_context where session_id=? for update",(r,n)->r.getTimestamp(1),scope.sessionId());
        if (contexts.isEmpty() || contexts.get(0) != null) throw new IllegalStateException("NOT_FOUND");
        Long tasks = jdbc.query("select id from agent_task where id=? and session_id=? and user_id=? for update",
                (r,n) -> 1L,scope.runId(),scope.sessionId(),scope.userId()).stream().findFirst().orElse(0L);
        if (tasks != 1) throw new IllegalStateException("NOT_FOUND");
    }

    private static Long number(ResultSet row,String column) throws SQLException { long value=row.getLong(column);return row.wasNull()?null:value; }
    private static Timestamp time(Instant at) { return Timestamp.from(at.truncatedTo(java.time.temporal.ChronoUnit.MICROS)); }
    private static String text(String value,int limit) { if(value==null||value.isBlank()||value.length()>limit)throw new IllegalArgumentException("MODEL_CALL_INVALID");return value; }
    private static void optionalText(String value,int limit) { if(value!=null)text(value,limit); }
    private static void nonnegative(Long value) { if(value!=null&&value<0)throw new IllegalArgumentException("MODEL_CALL_INVALID"); }
    private static void validate(HistoryStore.Scope scope,String key,String purpose) { Objects.requireNonNull(scope);text(scope.userId(),36);text(scope.sessionId(),36);text(scope.runId(),36);text(key,128);text(purpose,32); }
    private record Reported(String purpose,String model,String status,Long input,Long output,Long cached,Long reasoning,Timestamp finishedAt) { }
    private record Aggregate(boolean complete,ContextHistoryService.Usage actual,ContextHistoryService.Usage known) { }
    private record Estimate(long tokens,String source,int outputReserveTokens,int safetyTokens) { }
}
