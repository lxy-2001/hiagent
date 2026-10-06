package com.agentflow.web.support;
import java.time.Instant;

/** Auth revocation and request throttling have a local or Redis boundary. */
public interface WebRequestStateStore {
    boolean isAccessRevoked(String tokenId);
    void revokeAccess(String tokenId, Instant expiresAt);
    long incrementRate(String key, Instant expiresAt);
}
