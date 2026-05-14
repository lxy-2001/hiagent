package com.agentflow.web.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sys_user")
public class SysUser {

    @Id
    private String id;
    private String username;
    private String passwordHash;
    private String displayName;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    protected SysUser() {
    }

    public SysUser(String id, String username, String passwordHash, String displayName, boolean enabled, Instant now) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.enabled = enabled;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
