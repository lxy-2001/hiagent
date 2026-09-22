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

    private final StringRedisTemplate redisTemplate;
    private final RunApiErrorWriter errors;

    public JwtBlacklistFilter(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new RunApiErrorWriter(new ObjectMapper()));
    }

    public JwtBlacklistFilter(StringRedisTemplate redisTemplate, RunApiErrorWriter errors) {
        this.redisTemplate = redisTemplate;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt && jwt.getId() != null) {
            Boolean blacklisted;
            try {
                blacklisted = redisTemplate.hasKey("auth:blacklist:" + jwt.getId());
            } catch (RuntimeException dependencyFailure) {
                if (isRunOrSessionPath(request)) { errors.write(response, 503, "DEPENDENCY_UNAVAILABLE", null); return; }
                throw dependencyFailure;
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
                || request.getRequestURI().startsWith("/api/agent/sessions/");
    }
}
