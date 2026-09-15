package com.agentflow.web.run;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

public final class RunApiErrorWriter {
    public record ErrorResponse(String code, String message, String taskId) { }
    private final ObjectMapper mapper;
    public RunApiErrorWriter(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }
    public void write(HttpServletResponse response, int status, String code, String taskId) throws IOException {
        response.resetBuffer(); response.setStatus(status); response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8"); response.setHeader("Cache-Control", "no-store");
        if (taskId != null) response.setHeader("Location", "/api/agent/tasks/" + taskId);
        mapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message(code), taskId));
    }
    public static String message(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "The request is invalid.";
            case "UNAUTHORIZED" -> "Authentication is required.";
            case "NOT_FOUND" -> "The run was not found.";
            case "RATE_LIMITED" -> "Too many requests.";
            case "RUN_CAPACITY_EXCEEDED" -> "Run capacity is currently full.";
            case "SUBSCRIPTION_CAPACITY_EXCEEDED" -> "Event subscription capacity is currently full.";
            case "INVALID_EVENT_CURSOR" -> "The event cursor is invalid.";
            case "EVENT_HISTORY_UNAVAILABLE" -> "Event history is unavailable; query the run instead.";
            case "SERVICE_STOPPING" -> "The service is stopping.";
            case "PERSISTENCE_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE" -> "A required dependency is unavailable.";
            default -> "The request could not be completed.";
        };
    }
}
