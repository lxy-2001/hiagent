package com.agentflow.web.context;

import com.agentflow.llm.ResponsesContinuationStore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="agent_responses_continuation", uniqueConstraints=@UniqueConstraint(name="uq_continuation_request",columnNames={"run_id","request_key"}))
public class ResponsesContinuationEntity {
    @Id @Column(length=36) private String id;
    @Column(name="owner_id",length=36,nullable=false) private String ownerId;
    @Column(name="session_id",length=36,nullable=false) private String sessionId;
    @Column(name="run_id",length=36,nullable=false) private String runId;
    @Column(length=100,nullable=false) private String provider;
    @Column(length=100,nullable=false) private String model;
    @Column(name="request_key",length=128,nullable=false) private String requestKey;
    @Column(name="payload_hash",length=64,nullable=false) private String payloadHash;
    @Column(columnDefinition="longtext",nullable=false) private String payload;
    @Column(name="payload_bytes",nullable=false) private long payloadBytes;
    @Column(name="reasoning_token_hint") private Long reasoningTokenHint;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ResponsesContinuationEntity() { }
    public ResponsesContinuationEntity(String id,ResponsesContinuationStore.Scope scope,String requestKey,String hash,String payload,long bytes,Long hint,Instant time) {
        this.id=id;ownerId=scope.userId();sessionId=scope.sessionId();runId=scope.runId();provider=scope.provider();model=scope.model();this.requestKey=requestKey;
        payloadHash=hash;this.payload=payload;payloadBytes=bytes;reasoningTokenHint=hint;createdAt=time;
    }
}