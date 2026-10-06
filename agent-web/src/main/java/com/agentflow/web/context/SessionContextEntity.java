package com.agentflow.web.context;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_session_context")
public class SessionContextEntity {
    @Id @Column(name = "session_id", length = 36) private String sessionId;
    @Column(name = "last_record_sequence", nullable = false) private long lastRecordSequence;
    @Column(name = "active_checkpoint_id", length = 36) private String activeCheckpointId;
    @Column(name = "context_revision", nullable = false) private long contextRevision;
    @Column(name = "last_used_at", nullable = false) private Instant lastUsedAt;
    @Column(name = "deleted_at") private Instant deletedAt;
    protected SessionContextEntity() { }
    public SessionContextEntity(String sessionId, Instant lastUsedAt) { this.sessionId = sessionId; this.lastUsedAt = lastUsedAt; }
    public String getSessionId() { return sessionId; }
    public long getLastRecordSequence() { return lastRecordSequence; }
    public String getActiveCheckpointId() { return activeCheckpointId; }
    public long getContextRevision() { return contextRevision; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void appended(long lastSequence) { lastRecordSequence = lastSequence; contextRevision = Math.addExact(contextRevision, 1); }
    public void checkpoint(String id) { activeCheckpointId = id; contextRevision = Math.addExact(contextRevision, 1); }
    public void touch(Instant at) { if (at.isAfter(lastUsedAt)) lastUsedAt = at; }
    public void delete(Instant at) { deletedAt = at; }
}
