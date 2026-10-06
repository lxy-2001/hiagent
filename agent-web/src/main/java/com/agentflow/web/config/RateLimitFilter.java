package com.agentflow.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import com.agentflow.web.run.RunApiErrorWriter;
import tools.jackson.databind.ObjectMapper;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT_PER_MINUTE = 120;

    private final com.agentflow.web.support.WebRequestStateStore stateStore;
    private final RunApiErrorWriter errors;

    public RateLimitFilter(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new RunApiErrorWriter(new ObjectMapper()));
    }

    public RateLimitFilter(StringRedisTemplate redisTemplate, RunApiErrorWriter errors) {
        this(new com.agentflow.web.support.RedisWebRequestStateStore(redisTemplate), errors);
    }

    public RateLimitFilter(com.agentflow.web.support.WebRequestStateStore stateStore, RunApiErrorWriter errors) {
        this.stateStore = stateStore;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = "rate:" + clientIp(request) + ":" + Instant.now().getEpochSecond() / 60;
        Long count;
        try {
            count = stateStore.incrementRate(key, Instant.now().plus(Duration.ofMinutes(2)));
        } catch (RuntimeException dependencyFailure) {
            errors.write(response, 503, "DEPENDENCY_UNAVAILABLE", null); return;
        }
        if (count != null && count > LIMIT_PER_MINUTE) {
            if (isRunOrSessionPath(request)) errors.write(response, 429, "RATE_LIMITED", null);
            else response.sendError(429, "Too many requests");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isRunOrSessionPath(HttpServletRequest request) {
        return request.getRequestURI().equals("/api/agent/tasks")
                || request.getRequestURI().startsWith("/api/agent/tasks/")
                || request.getRequestURI().equals("/api/agent/sessions")
                || request.getRequestURI().startsWith("/api/agent/sessions/")
                || request.getRequestURI().startsWith("/api/agent/artifacts/");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
