package com.agentflow.web.context;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_history_batch", uniqueConstraints = @UniqueConstraint(name = "uq_history_batch_mutation", columnNames = {"session_id", "mutation_id"}))
public class HistoryBatchEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "session_id", length = 36, nullable = false) private String sessionId;
    @Column(name = "mutation_id", length = 128, nullable = false) private String mutationId;
    @Column(name = "payload_hash", length = 64, nullable = false) private String payloadHash;
    @Column(name = "first_sequence", nullable = false) private long firstSequence;
    @Column(name = "last_sequence", nullable = false) private long lastSequence;
    @Column(name = "context_revision", nullable = false) private long contextRevision;
    @Column(name = "committed_at", nullable = false) private Instant committedAt;
    protected HistoryBatchEntity() { }
    public HistoryBatchEntity(String id, String sessionId, String mutationId, String payloadHash, long firstSequence, long lastSequence, long contextRevision, Instant committedAt) {
        this.id=id; this.sessionId=sessionId; this.mutationId=mutationId; this.payloadHash=payloadHash; this.firstSequence=firstSequence; this.lastSequence=lastSequence; this.contextRevision=contextRevision; this.committedAt=committedAt;
    }
    public String getPayloadHash() { return payloadHash; }
    public long getFirstSequence() { return firstSequence; }
    public long getLastSequence() { return lastSequence; }
    public long getContextRevision() { return contextRevision; }
}
