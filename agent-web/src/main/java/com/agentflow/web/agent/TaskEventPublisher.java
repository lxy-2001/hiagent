package com.agentflow.web.agent;

import com.agentflow.core.AgentEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TaskEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public TaskEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(AgentEvent event) {
        String payload = toJson(event);
        String key = key(event.taskId());
        redisTemplate.opsForList().rightPush(key, payload);
        redisTemplate.opsForList().trim(key, -100, -1);
        redisTemplate.expire(key, Duration.ofHours(2));
        for (SseEmitter emitter : emitters.getOrDefault(event.taskId(), new CopyOnWriteArrayList<>())) {
            try {
                emitter.send(SseEmitter.event().name(event.type().name()).data(payload));
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        List<String> cached = redisTemplate.opsForList().range(key(taskId), 0, -1);
        if (cached != null) {
            cached.forEach(payload -> {
                try {
                    emitter.send(SseEmitter.event().name("REPLAY").data(payload));
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            });
        }
        return emitter;
    }

    public void complete(String taskId) {
        for (SseEmitter emitter : emitters.getOrDefault(taskId, new CopyOnWriteArrayList<>())) {
            emitter.complete();
        }
        emitters.remove(taskId);
    }

    public void error(String taskId, Throwable throwable) {
        for (SseEmitter emitter : emitters.getOrDefault(taskId, new CopyOnWriteArrayList<>())) {
            emitter.completeWithError(throwable);
        }
        emitters.remove(taskId);
    }

    private void remove(String taskId, SseEmitter emitter) {
        emitters.getOrDefault(taskId, new CopyOnWriteArrayList<>()).remove(emitter);
    }

    private String key(String taskId) {
        return "agent:task:events:" + taskId;
    }

    private String toJson(AgentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize event", ex);
        }
    }
}
