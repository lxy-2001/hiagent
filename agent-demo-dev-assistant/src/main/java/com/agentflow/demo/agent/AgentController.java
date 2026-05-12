package com.agentflow.demo.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentTaskService taskService;

    public AgentController(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/tasks")
    public TaskResponse createTask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(jwt.getSubject(), request.input());
    }

    @GetMapping("/tasks/{taskId}")
    public TaskDetail task(@PathVariable String taskId) {
        return taskService.getTask(taskId);
    }

    @GetMapping("/tasks/{taskId}/steps")
    public List<StepResponse> steps(@PathVariable String taskId) {
        return taskService.getSteps(taskId);
    }

    @GetMapping("/tasks/{taskId}/events")
    public SseEmitter events(@PathVariable String taskId) {
        return taskService.subscribe(taskId);
    }

    public record CreateTaskRequest(@NotBlank String input) {
    }

    public record TaskResponse(String taskId, String sessionId, String status) {
    }

    public record TaskDetail(String taskId, String sessionId, String status, String input, String finalAnswer) {
    }

    public record StepResponse(int stepNo, String stepType, String toolName, String status, String output, String errorMessage) {
    }
}
