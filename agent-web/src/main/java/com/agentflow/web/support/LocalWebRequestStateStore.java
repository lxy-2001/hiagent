package com.agentflow.web.support;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Durable local access revocation, and at most 4096 short-lived rate counters. */
public class LocalWebRequestStateStore implements WebRequestStateStore {
    private static final int MAX_COUNTERS = 4096;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Counter> counters = new HashMap<>();
    private record Counter(long count, Instant expiresAt) { }
    public LocalWebRequestStateStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.clock = Objects.requireNonNull(clock);
    }
    public boolean isAccessRevoked(String tokenId) {
        requireId(tokenId);
        Integer count = jdbc.queryForObject("select count(*) from auth_access_revocation where jti=? and expires_at>?",
                Integer.class, tokenId, Timestamp.from(clock.instant()));
        return count != null && count > 0;
    }
    public void revokeAccess(String tokenId, Instant expiresAt) {
        requireId(tokenId); Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(clock.instant())) return;
        // Keep cleanup bounded; an expired row cannot revoke a valid token indefinitely.
        var expired = jdbc.queryForList("select jti from auth_access_revocation where expires_at<=? order by expires_at,jti fetch first 100 rows only",
                String.class, Timestamp.from(clock.instant()));
        for (String id : expired) jdbc.update("delete from auth_access_revocation where jti=? and expires_at<=?", id, Timestamp.from(clock.instant()));
        try { jdbc.update("insert into auth_access_revocation(jti,expires_at) values(?,?)", tokenId, Timestamp.from(expiresAt)); }
        catch (DuplicateKeyException duplicate) {
            jdbc.update("update auth_access_revocation set expires_at=? where jti=? and expires_at<?",
                    Timestamp.from(expiresAt), tokenId, Timestamp.from(expiresAt));
        }
    }
    public synchronized long incrementRate(String key, Instant expiresAt) {
        if (key == null || key.isBlank() || key.length() > 256) throw new IllegalArgumentException("invalid rate key");
        Instant now = clock.instant();
        counters.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Counter previous = counters.get(key);
        if (previous == null && counters.size() >= MAX_COUNTERS) throw new IllegalStateException("rate capacity exhausted");
        long count = previous == null ? 1 : Math.min(121, previous.count() + 1);
        counters.put(key, new Counter(count, expiresAt));
        return count;
    }
    private static void requireId(String tokenId) {
        if (tokenId == null || tokenId.isBlank() || tokenId.length() > 128) throw new IllegalArgumentException("invalid token id");
    }
}
