package com.agentflow.web.context;

import com.agentflow.core.context.HistoryStore;
import com.agentflow.llm.ResponsesContinuationStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Keeps provider data opaque and bounded; visible messages carry references only. */
public class JpaResponsesContinuationStore implements ResponsesContinuationStore {
    private static final long MAX_BYTES=8L*1024*1024;
    private final JpaHistoryStore history;
    private final EntityManager manager;
    private final ObjectMapper mapper;
    public JpaResponsesContinuationStore(JpaHistoryStore history,EntityManager manager,ObjectMapper mapper) {
        this.history=Objects.requireNonNull(history);this.manager=Objects.requireNonNull(manager);this.mapper=Objects.requireNonNull(mapper);
    }
    @Override @Transactional(timeout=3)
    public String stage(Scope scope,String requestKey,JsonNode output,Long hint,HistoryStore.Io io) {
        io.check(); validateScope(scope); text(requestKey,128);Objects.requireNonNull(output);
        if(!output.isArray() || hint!=null&&hint<0) throw new IllegalArgumentException("CONTINUATION_INVALID");
        String payload=mapper.writeValueAsString(output);
        long bytes=payload.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>MAX_BYTES) throw new IllegalStateException("CONTINUATION_TOO_LARGE");
        String hash=hash(payload);
        history.lockContext(localScope(scope));
        List<Tuple> previous=manager.createQuery("select c.id as id,c.ownerId as owner,c.sessionId as session,c.provider as provider,c.model as model,c.payloadHash as hash,c.reasoningTokenHint as hint from ResponsesContinuationEntity c where c.runId=:run and c.requestKey=:key",Tuple.class)
                .setParameter("run",scope.runId()).setParameter("key",requestKey).setMaxResults(1).getResultList();
        if(!previous.isEmpty()) {
            Tuple row=previous.get(0);
            if(!scope.userId().equals(row.get("owner")) || !scope.sessionId().equals(row.get("session")) || !scope.provider().equals(row.get("provider"))
                    || !scope.model().equals(row.get("model")) || !hash.equals(row.get("hash")) || !Objects.equals(hint,row.get("hint")))
                throw new IllegalStateException("CONTINUATION_MUTATION_CONFLICT");
            return row.get("id",String.class);
        }
        String id=UUID.randomUUID().toString();
        manager.persist(new ResponsesContinuationEntity(id,scope,requestKey,hash,payload,bytes,hint,Instant.now()));
        io.check();manager.flush();return id;
    }
    @Override @Transactional(readOnly=true,timeout=3)
    public Optional<JsonNode> read(Scope scope,String reference,HistoryStore.Io io) {
        io.check();validateScope(scope);text(reference,36);requireOwned(scope);
        List<Object[]> metadata=manager.createQuery("select c.payloadBytes,c.provider,c.model from ResponsesContinuationEntity c where c.id=:id and c.ownerId=:owner and c.sessionId=:session",Object[].class)
                .setParameter("id",reference).setParameter("owner",scope.userId()).setParameter("session",scope.sessionId()).setMaxResults(1).getResultList();
        if(metadata.isEmpty())throw new com.agentflow.core.context.ContextSessionException("CONTINUATION_UNAVAILABLE");
        Object[] row=metadata.get(0);
        if(!scope.provider().equals(row[1]) || !scope.model().equals(row[2]))return Optional.empty();
        long size=((Number)row[0]).longValue();
        if(size<0 || size>MAX_BYTES)throw new IllegalStateException("CONTINUATION_TOO_LARGE");
        String payload=manager.createQuery("select c.payload from ResponsesContinuationEntity c where c.id=:id and c.ownerId=:owner and c.sessionId=:session and c.provider=:provider and c.model=:model",String.class)
                .setParameter("id",reference).setParameter("owner",scope.userId()).setParameter("session",scope.sessionId()).setParameter("provider",scope.provider()).setParameter("model",scope.model()).getSingleResult();
        io.check();if(payload.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalStateException("CONTINUATION_TOO_LARGE");
        return Optional.of(mapper.readTree(payload));
    }
    @Override @Transactional(timeout=3)
    public void prune(Scope scope,HistoryStore.Io io) {
        io.check();validateScope(scope);
        var context=history.lockContext(localScope(scope));
        long covered=0;
        if(context.getActiveCheckpointId()!=null) {
            List<Long> coverage=manager.createQuery("select c.coveredThroughSequence from ContextCheckpointEntity c where c.id=:id and c.sessionId=:session",Long.class)
                    .setParameter("id",context.getActiveCheckpointId()).setParameter("session",scope.sessionId()).setMaxResults(1).getResultList();
            if(coverage.isEmpty())throw new IllegalStateException("HISTORY_CHECKPOINT_MISSING");
            covered=coverage.get(0);
        }
        // Covered refs are obsolete. Unconfirmed current-call state remains protected until history confirms it.
        // Same-session older staged orphans are removable only after their Run is a terminal immutable fact.
        manager.createQuery("delete from ResponsesContinuationEntity c where c.ownerId=:owner and c.sessionId=:session and ("
                + "exists(select r.id from HistoryRecordEntity r where r.sessionId=c.sessionId and r.adapterStateRef=c.id and r.recordSequence<=:covered)"
                + " and not exists(select r2.id from HistoryRecordEntity r2 where r2.sessionId=c.sessionId and r2.adapterStateRef=c.id and r2.recordSequence>:covered)"
                + " or c.runId<>:current and exists(select t.id from AgentTaskEntity t where t.id=c.runId and t.status in ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED'))"
                + " and (not exists(select r3.id from HistoryRecordEntity r3 where r3.sessionId=c.sessionId and r3.adapterStateRef=c.id and r3.recordSequence>:covered)))")
                .setParameter("owner",scope.userId()).setParameter("session",scope.sessionId()).setParameter("covered",covered)
                .setParameter("current",scope.runId()).executeUpdate();
        io.check();manager.flush();
    }
    private void requireOwned(Scope scope) {
        Long count=manager.createQuery("select count(t) from AgentTaskEntity t,AgentSessionEntity s where t.id=:run and t.sessionId=:session and t.userId=:owner and s.id=t.sessionId and s.userId=:owner and not exists(select c.sessionId from SessionContextEntity c where c.sessionId=s.id and c.deletedAt is not null)",Long.class)
                .setParameter("run",scope.runId()).setParameter("session",scope.sessionId()).setParameter("owner",scope.userId()).getSingleResult();
        if(count!=1)throw new IllegalStateException("NOT_FOUND");
    }
    private static void validateScope(Scope scope) { Objects.requireNonNull(scope);text(scope.userId(),36);text(scope.sessionId(),36);text(scope.runId(),36);text(scope.provider(),100);text(scope.model(),100); }
    private static HistoryStore.Scope localScope(Scope s){return new HistoryStore.Scope(s.userId(),s.sessionId(),s.runId());}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("CONTINUATION_INVALID");}
    private static String hash(String payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}