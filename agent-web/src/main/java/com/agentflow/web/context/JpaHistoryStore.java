package com.agentflow.web.context;

import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentTaskEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Short reliable transactions. Body reads occur only after bounded metadata admission. */
public class JpaHistoryStore implements HistoryStore {
    public static final int MAX_PAGE_RECORDS = 128;
    public static final long MAX_PAGE_BYTES = 16L * 1024 * 1024;
    private final AgentSessionRepository sessions;
    private final EntityManager manager;
    private final ObjectMapper mapper;

    public JpaHistoryStore(AgentSessionRepository sessions, EntityManager manager, ObjectMapper mapper) {
        this.sessions = Objects.requireNonNull(sessions);
        this.manager = Objects.requireNonNull(manager);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override @Transactional(readOnly=true, timeout=3)
    public State state(Scope scope, Io io) {
        io.check(); requireOwned(scope);
        SessionContextEntity context = manager.find(SessionContextEntity.class, scope.sessionId());
        if (context == null) return new State(0, 0, null);
        requireVisible(context);
        Checkpoint checkpoint = context.getActiveCheckpointId() == null ? null : requireCheckpoint(context.getActiveCheckpointId());
        io.check();
        return new State(context.getLastRecordSequence(), context.getContextRevision(), checkpoint);
    }

    @Override @Transactional(readOnly=true, timeout=3)
    public Page readAfter(Scope scope, long after, long until, int limit, long byteBudget, Io io) {
        if (after < 0 || until < after || limit < 1 || limit > MAX_PAGE_RECORDS || byteBudget < 1 || byteBudget > MAX_PAGE_BYTES)
            throw new IllegalArgumentException("invalid history page");
        io.check(); requireOwned(scope);
        SessionContextEntity context = manager.find(SessionContextEntity.class, scope.sessionId());
        if (context != null) requireVisible(context);
        // Do not select entities here: selecting an entity would eagerly materialize its text body.
        List<Tuple> metadata = manager.createQuery("select r.id as id, r.runId as runId, r.recordSequence as sequence, r.kind as kind, r.occurredAt as occurredAt, r.decisionId as decisionId, r.callId as callId, r.status as status, r.artifactId as artifactId, r.adapterStateRef as adapterStateRef, r.payloadBytes as bytes from HistoryRecordEntity r where r.sessionId=:session and r.recordSequence>:after and r.recordSequence<=:until order by r.recordSequence", Tuple.class)
                .setParameter("session", scope.sessionId()).setParameter("after", after).setParameter("until", until)
                .setMaxResults(limit + 1).getResultList();
        List<Tuple> admitted = new ArrayList<>();
        long bytes = 0;
        for (Tuple row : metadata) {
            long size = row.get("bytes", Long.class);
            if (size < 0 || size > MAX_PAGE_BYTES) throw new IllegalStateException("HISTORY_RECORD_INVALID");
            if (admitted.size() == limit || size > byteBudget - bytes) break;
            admitted.add(row); bytes += size;
        }
        if (admitted.isEmpty() && !metadata.isEmpty()) throw new IllegalStateException("HISTORY_PAGE_TOO_LARGE");
        if (admitted.isEmpty()) return new Page(List.of(), after, false);
        List<String> ids = admitted.stream().map(row -> row.get("id", String.class)).toList();
        Map<String,String> payloads = new HashMap<>();
        for (Tuple body : manager.createQuery("select r.id as id, r.inlinePayload as payload from HistoryRecordEntity r where r.id in :ids and r.sessionId=:session", Tuple.class)
                .setParameter("ids", ids).setParameter("session", scope.sessionId()).setMaxResults(limit).getResultList()) {
            payloads.put(body.get("id", String.class), body.get("payload", String.class));
        }
        List<Record> records = new ArrayList<>();
        for (Tuple row : admitted) {
            io.check(); String id = row.get("id", String.class);
            if (!payloads.containsKey(id)) throw new IllegalStateException("HISTORY_RECORD_MISSING");
            ModelMessage message = decode(payloads.get(id));
            Item item = new Item(Kind.valueOf(row.get("kind",String.class)), message, row.get("occurredAt",Instant.class),
                    row.get("decisionId",String.class), row.get("callId",String.class), row.get("status",String.class),
                    row.get("artifactId",String.class), row.get("adapterStateRef",String.class));
            records.add(new Record(row.get("sequence",Long.class),row.get("runId",String.class),item,row.get("bytes",Long.class)));
        }
        return new Page(records, records.get(records.size()-1).sequence(), metadata.size() > admitted.size());
    }

    @Override @Transactional(timeout=3)
    public Batch appendBatch(Scope scope, String mutationId, String payloadHash, List<Item> items, Io io) {
        io.check(); requireText(mutationId,128,"mutationId"); requireText(payloadHash,64,"payloadHash");
        List<Item> confirmed = List.copyOf(items);
        if (confirmed.isEmpty() || confirmed.size() > MAX_PAGE_RECORDS) throw new IllegalArgumentException("invalid history batch");
        // Encoding and size validation precede row locks; no unbounded text is materialized under a lock.
        List<String> payloads = confirmed.stream().map(item -> encode(item.message())).toList();
        List<Long> sizes = payloads.stream().map(body -> body == null ? 0L : (long)body.getBytes(StandardCharsets.UTF_8).length).toList();
        long bytes = 0;
        for (long size : sizes) { bytes = Math.addExact(bytes,size); if (bytes > MAX_PAGE_BYTES) throw new IllegalStateException("HISTORY_BATCH_TOO_LARGE"); }
        SessionContextEntity context = lockContext(scope);
        List<HistoryBatchEntity> existing = manager.createQuery("select b from HistoryBatchEntity b where b.sessionId=:session and b.mutationId=:mutation",HistoryBatchEntity.class)
                .setParameter("session",scope.sessionId()).setParameter("mutation",mutationId).setMaxResults(1).getResultList();
        if (!existing.isEmpty()) {
            HistoryBatchEntity batch = existing.get(0);
            if (!payloadHash.equals(batch.getPayloadHash())) throw new IllegalStateException("HISTORY_MUTATION_CONFLICT");
            return new Batch(batch.getFirstSequence(),batch.getLastSequence(),batch.getContextRevision());
        }
        validateContinuationReferences(scope, confirmed);
        long first = Math.addExact(context.getLastRecordSequence(),1);
        long last = Math.addExact(context.getLastRecordSequence(),confirmed.size());
        context.appended(last);
        for (int i=0;i<confirmed.size();i++) {
            manager.persist(new HistoryRecordEntity(UUID.randomUUID().toString(),scope.sessionId(),scope.runId(),first+i,confirmed.get(i),payloads.get(i),sizes.get(i)));
        }
        manager.persist(new HistoryBatchEntity(UUID.randomUUID().toString(),scope.sessionId(),mutationId,payloadHash,first,last,context.getContextRevision(),Instant.now()));
        io.check(); manager.flush();
        return new Batch(first,last,context.getContextRevision());
    }

    @Override @Transactional(timeout=3)
    public Checkpoint saveCheckpoint(Scope scope,long expectedRevision,Checkpoint checkpoint,Io io) {
        io.check(); Objects.requireNonNull(checkpoint, "checkpoint");
        requireText(checkpoint.id(), 36, "checkpoint id");
        requireText(checkpoint.summary(), Integer.MAX_VALUE, "checkpoint summary");
        requireText(checkpoint.requestKey(), 128, "request key");
        requireText(checkpoint.promptVersion(), 64, "prompt version");
        requireText(checkpoint.profileHash(), 64, "profile hash");
        Objects.requireNonNull(checkpoint.createdAt(), "checkpoint time");
        if (expectedRevision < 0 || checkpoint.revision() != Math.addExact(expectedRevision, 1)
                || checkpoint.coveredThrough() < 1 || checkpoint.tailStart() != Math.addExact(checkpoint.coveredThrough(), 1)
                || checkpoint.summaryTokens() < 0 || checkpoint.sourceTokens() < 0 || checkpoint.retainedTokens() < 0
                || checkpoint.summary().getBytes(StandardCharsets.UTF_8).length > MAX_PAGE_BYTES) {
            throw new IllegalArgumentException("CHECKPOINT_INVALID");
        }
        SessionContextEntity context = lockContext(scope);
        // A retry after a committed write returns the original confirmation before testing the now-stale CAS.
        ContextCheckpointEntity existing = manager.find(ContextCheckpointEntity.class, checkpoint.id());
        if (existing != null) {
            if (!scope.sessionId().equals(existing.getSessionId()) || !sameCheckpoint(existing.toCheckpoint(), checkpoint)) {
                throw new IllegalStateException("CHECKPOINT_MUTATION_CONFLICT");
            }
            return existing.toCheckpoint();
        }
        if (context.getContextRevision() != expectedRevision
                || !Objects.equals(context.getActiveCheckpointId(), checkpoint.baseId())) {
            throw new IllegalStateException("CHECKPOINT_REVISION_CONFLICT");
        }
        long previousCoverage = context.getActiveCheckpointId() == null ? 0
                : requireCheckpoint(context.getActiveCheckpointId()).coveredThrough();
        if (checkpoint.coveredThrough() < previousCoverage || checkpoint.coveredThrough() > context.getLastRecordSequence()) {
            throw new IllegalStateException("CHECKPOINT_COVERAGE_INVALID");
        }
        validateCoverage(scope.sessionId(), previousCoverage, checkpoint.coveredThrough(), io);
        Checkpoint confirmed = new Checkpoint(checkpoint.id(), checkpoint.revision(), checkpoint.baseId(), checkpoint.coveredThrough(),
                checkpoint.summary(), checkpoint.summaryTokens(), checkpoint.sourceTokens(), checkpoint.retainedTokens(), checkpoint.tailStart(),
                checkpoint.requestKey(), checkpoint.promptVersion(), checkpoint.profileHash(),
                checkpoint.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        manager.persist(new ContextCheckpointEntity(scope.sessionId(), confirmed));
        context.checkpoint(confirmed.id());
        io.check(); manager.flush();
        return confirmed;
    }

    private static boolean sameCheckpoint(Checkpoint left, Checkpoint right) {
        // H2/Flyway store timestamps at microsecond precision; it is not a mutation of summary content.
        return Objects.equals(left.id(), right.id()) && left.revision() == right.revision()
                && Objects.equals(left.baseId(), right.baseId()) && left.coveredThrough() == right.coveredThrough()
                && Objects.equals(left.summary(), right.summary()) && left.summaryTokens() == right.summaryTokens()
                && left.sourceTokens() == right.sourceTokens() && left.retainedTokens() == right.retainedTokens()
                && left.tailStart() == right.tailStart() && Objects.equals(left.requestKey(), right.requestKey())
                && Objects.equals(left.promptVersion(), right.promptVersion()) && Objects.equals(left.profileHash(), right.profileHash())
                && left.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                    .equals(right.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    /** Only scalar call metadata is scanned; covered message bodies never re-enter the working set. */
    private void validateCoverage(String session, long after, long covered, Io io) {
        Set<String> pending = new HashSet<>();
        while (after < covered) {
            io.check();
            List<Tuple> page = manager.createQuery("select r.recordSequence as sequence, r.runId as runId, r.kind as kind, r.callId as callId from HistoryRecordEntity r where r.sessionId=:session and r.recordSequence>:after and r.recordSequence<=:covered order by r.recordSequence", Tuple.class)
                    .setParameter("session", session).setParameter("after", after).setParameter("covered", covered)
                    .setMaxResults(MAX_PAGE_RECORDS).getResultList();
            if (page.isEmpty()) throw new IllegalStateException("CHECKPOINT_COVERAGE_INVALID");
            for (Tuple row : page) {
                long sequence = row.get("sequence", Long.class);
                if (sequence != after + 1) throw new IllegalStateException("CHECKPOINT_COVERAGE_INVALID");
                Kind kind = Kind.valueOf(row.get("kind", String.class));
                String callId = row.get("callId", String.class);
                String callKey = row.get("runId", String.class) + ":" + callId;
                if (kind == Kind.TOOL_CALL && (callId == null || !pending.add(callKey))) {
                    throw new IllegalStateException("CHECKPOINT_COVERAGE_INVALID");
                }
                if ((kind == Kind.TOOL_RESULT || kind == Kind.RECOVERY_NOTICE) && callId != null && !pending.remove(callKey)) {
                    throw new IllegalStateException("CHECKPOINT_COVERAGE_INVALID");
                }
                after = sequence;
            }
        }
        if (!pending.isEmpty()) throw new IllegalStateException("CHECKPOINT_COVERAGE_INVALID");
    }

    public record UnconfirmedCall(long sequence, String runId, String callId, String toolName, String decisionId) { }

    /** Recovery uses scalar metadata of terminal older runs and never loads their tool arguments. */
    @Transactional(readOnly = true, timeout = 3)
    public List<UnconfirmedCall> unconfirmedCalls(Scope scope, long after, long until, int limit, Io io) {
        if (after < 0 || until < after || limit < 1 || limit > MAX_PAGE_RECORDS) throw new IllegalArgumentException("invalid recovery page");
        io.check(); requireOwned(scope);
        SessionContextEntity context = manager.find(SessionContextEntity.class, scope.sessionId());
        if (context != null) requireVisible(context);
        List<Tuple> rows = manager.createQuery("select r.recordSequence as sequence, r.runId as runId, r.callId as callId, r.toolName as toolName, r.decisionId as decisionId from HistoryRecordEntity r, AgentTaskEntity t where r.sessionId=:session and r.runId=t.id and r.runId<>:currentRun and t.status in ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED') and r.kind='TOOL_CALL' and r.recordSequence>:after and r.recordSequence<=:until and not exists (select result.id from HistoryRecordEntity result where result.sessionId=r.sessionId and result.runId=r.runId and result.callId=r.callId and result.kind in ('TOOL_RESULT','RECOVERY_NOTICE')) order by r.recordSequence", Tuple.class)
                .setParameter("session", scope.sessionId()).setParameter("currentRun", scope.runId())
                .setParameter("after", after).setParameter("until", until).setMaxResults(limit).getResultList();
        io.check();
        return rows.stream().map(row -> new UnconfirmedCall(row.get("sequence", Long.class), row.get("runId", String.class),
                row.get("callId", String.class), row.get("toolName", String.class), row.get("decisionId", String.class))).toList();
    }

    /** Shared by create/complete so they acquire session, context, then task, in that order. */
    @Transactional(timeout=3)
    public SessionContextEntity lockContext(Scope scope) { return lockContext(scope, true); }

    /** New-run admission locks the session before its task row exists. */
    @Transactional(timeout=3)
    public SessionContextEntity lockContext(Scope scope, boolean requireRun) {
        sessions.findOwnedForUpdate(scope.sessionId(),scope.userId()).orElseThrow(() -> new IllegalStateException("NOT_FOUND"));
        SessionContextEntity context = manager.find(SessionContextEntity.class,scope.sessionId(),LockModeType.PESSIMISTIC_WRITE);
        if (context == null) { context = new SessionContextEntity(scope.sessionId(),Instant.now()); manager.persist(context); manager.flush(); }
        requireVisible(context);
        if (requireRun) {
            requireRun(scope);
        }
        return context;
    }

    private void requireOwned(Scope scope) {
        sessions.findByIdAndUserId(scope.sessionId(),scope.userId()).orElseThrow(() -> new IllegalStateException("NOT_FOUND"));
        requireRun(scope);
    }
    private void requireRun(Scope scope) {
        // Scalar validation must not cache an unlocked task before the terminal transaction locks it.
        long count = manager.createQuery("select count(t) from AgentTaskEntity t where t.id=:run and t.sessionId=:session and t.userId=:owner", Long.class)
                .setParameter("run", scope.runId()).setParameter("session", scope.sessionId()).setParameter("owner", scope.userId()).getSingleResult();
        if (count != 1) throw new IllegalStateException("NOT_FOUND");
    }
    private static void requireVisible(SessionContextEntity context) { if (context.getDeletedAt()!=null) throw new IllegalStateException("NOT_FOUND"); }
    private Checkpoint requireCheckpoint(String id) {
        ContextCheckpointEntity entity=manager.find(ContextCheckpointEntity.class,id);
        if(entity==null) throw new IllegalStateException("HISTORY_CHECKPOINT_MISSING");
        return entity.toCheckpoint();
    }
    private String encode(ModelMessage message) {
        if(message==null) return null;
        ToolCall call=message.toolCall();
        return mapper.writeValueAsString(new MessagePayload(message.role(),message.content(),message.name(),message.toolCallId(),
                call==null?null:call.callId(),call==null?null:call.name(),call==null?null:call.arguments().values()));
    }
    private void validateContinuationReferences(Scope scope, List<Item> items) {
        for (String reference : items.stream().map(Item::adapterStateRef).filter(Objects::nonNull).distinct().toList()) {
            if (reference.isBlank() || reference.length() > 36) {
                throw new IllegalStateException("CONTINUATION_REFERENCE_INVALID");
            }
            long count = manager.createQuery("select count(c) from ResponsesContinuationEntity c where c.id=:id and c.ownerId=:owner and c.sessionId=:session and c.runId=:run", Long.class)
                    .setParameter("id", reference).setParameter("owner", scope.userId())
                    .setParameter("session", scope.sessionId()).setParameter("run", scope.runId()).getSingleResult();
            if (count != 1) throw new IllegalStateException("CONTINUATION_REFERENCE_INVALID");
        }
    }

    private ModelMessage decode(String json) {
        if(json==null) return null;
        MessagePayload payload=mapper.readValue(json,MessagePayload.class);
        ToolCall call=payload.callId()==null?null:new ToolCall(payload.callId(),payload.toolName(),ToolArguments.managed(payload.arguments()));
        return new ModelMessage(payload.role(),payload.content(),payload.name(),payload.toolCallId(),call);
    }
    public String payloadHash(List<Item> items) {
        List<Map<String,Object>> canonical=new ArrayList<>();
        for(Item item:items) {
            Map<String,Object> fields=new LinkedHashMap<>();
            fields.put("kind",item.kind().name()); fields.put("message",encode(item.message())); fields.put("at",item.occurredAt().toString());
            fields.put("decision",item.decisionId()); fields.put("call",item.callId()); fields.put("status",item.status()); fields.put("artifact",item.artifactId()); fields.put("adapter",item.adapterStateRef());
            canonical.add(fields);
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireText(String text,int max,String field) { if(text==null||text.isBlank()||text.length()>max) throw new IllegalArgumentException(field+" invalid"); }
    private record MessagePayload(String role,String content,String name,String toolCallId,String callId,String toolName,Map<String,Object> arguments) { }
}
