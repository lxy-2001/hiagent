package com.agentflow.demo.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "auth_refresh_token")
public class AuthRefreshToken {

    @Id
    private String id;
    private String userId;
    private String tokenHash;
    private boolean revoked;
    private Instant expiresAt;
    private Instant createdAt;

    protected AuthRefreshToken() {
    }

    public AuthRefreshToken(String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.revoked = false;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void revoke() {
        this.revoked = true;
    }
}
