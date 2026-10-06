package com.agentflow.web.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Compatibility adapter for applications that still opt into Redis. */
public class RedisWebRequestStateStore implements WebRequestStateStore {
    private final StringRedisTemplate redis;
    public RedisWebRequestStateStore(StringRedisTemplate redis) { this.redis = Objects.requireNonNull(redis); }
    public boolean isAccessRevoked(String tokenId) { return Boolean.TRUE.equals(redis.hasKey("auth:blacklist:" + tokenId)); }
    public void revokeAccess(String tokenId, Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (!remaining.isNegative() && !remaining.isZero()) redis.opsForValue().set("auth:blacklist:" + tokenId, "1", remaining);
    }
    public long incrementRate(String key, Instant expiresAt) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) redis.expire(key, Duration.ofMinutes(2));
        if (count == null) throw new IllegalStateException("rate counter unavailable");
        return count;
    }
}
