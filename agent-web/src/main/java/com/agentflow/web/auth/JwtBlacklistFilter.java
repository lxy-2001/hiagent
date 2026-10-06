package com.agentflow.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import com.agentflow.web.run.RunApiErrorWriter;
import tools.jackson.databind.ObjectMapper;

public class JwtBlacklistFilter extends OncePerRequestFilter {

    private final com.agentflow.web.support.WebRequestStateStore stateStore;
    private final RunApiErrorWriter errors;

    public JwtBlacklistFilter(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new RunApiErrorWriter(new ObjectMapper()));
    }

    public JwtBlacklistFilter(StringRedisTemplate redisTemplate, RunApiErrorWriter errors) {
        this(new com.agentflow.web.support.RedisWebRequestStateStore(redisTemplate), errors);
    }

    public JwtBlacklistFilter(com.agentflow.web.support.WebRequestStateStore stateStore, RunApiErrorWriter errors) {
        this.stateStore = stateStore;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt && jwt.getId() != null) {
            Boolean blacklisted;
            try {
                blacklisted = stateStore.isAccessRevoked(jwt.getId());
            } catch (RuntimeException dependencyFailure) {
                errors.write(response, 503, "DEPENDENCY_UNAVAILABLE", null); return;
            }
            if (Boolean.TRUE.equals(blacklisted)) {
                SecurityContextHolder.clearContext();
                if (isRunOrSessionPath(request)) errors.write(response, 401, "UNAUTHORIZED", null);
                else response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token has been revoked");
                return;
            }
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
}
