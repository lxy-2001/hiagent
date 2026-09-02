package com.agentflow.web.agent;

import com.agentflow.core.memory.ShortTermMemory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

public class RedisShortTermMemory implements ShortTermMemory {

    private final StringRedisTemplate redisTemplate;

    public RedisShortTermMemory(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void appendUserMessage(String sessionId, String message) {
        append(sessionId, "USER: " + message);
    }

    @Override
    public void appendAssistantMessage(String sessionId, String message) {
        append(sessionId, "ASSISTANT: " + message);
    }

    @Override
    public List<String> recentMessages(String sessionId, int limit) {
        List<String> messages = redisTemplate.opsForList().range(key(sessionId), -Math.max(1, limit), -1);
        return messages == null ? List.of() : messages;
    }

    private void append(String sessionId, String message) {
        String key = key(sessionId);
        redisTemplate.opsForList().rightPush(key, message);
        redisTemplate.opsForList().trim(key, -20, -1);
        redisTemplate.expire(key, Duration.ofHours(12));
    }

    private String key(String sessionId) {
        return "memory:short:" + sessionId;
    }
}
