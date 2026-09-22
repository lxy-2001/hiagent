package com.agentflow.web.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "agent_confirmed_memory", uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "memory_key"}))
public class ConfirmedMemoryEntity {
    @Id private String id;
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    @Column(name = "memory_key", nullable = false, length = 32) private String key;
    @Column(name = "memory_value", length = 512) private String value;
    @Column(nullable = false, length = 16) private String state;
    @Column(nullable = false) private long version;
    @Column(length = 32) private String source;
    @Column(nullable = false) private Instant updatedAt;

    protected ConfirmedMemoryEntity() { }
    ConfirmedMemoryEntity(String id, String sessionId, String key) {
        this.id = id;
        this.sessionId = sessionId;
        this.key = key;
    }
    void update(String value, Instant now) {
        version = Math.addExact(version, 1);
        this.value = value;
        this.state = value == null ? "DELETED" : "ACTIVE";
        this.source = value == null ? null : "USER_CONFIRMED";
        this.updatedAt = now;
    }
    public String getSessionId() { return sessionId; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getState() { return state; }
    public long getVersion() { return version; }
    public String getSource() { return source; }
    public Instant getUpdatedAt() { return updatedAt; }
}
