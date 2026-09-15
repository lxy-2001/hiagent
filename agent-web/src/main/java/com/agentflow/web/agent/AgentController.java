package com.agentflow.web.agent;

import com.agentflow.web.run.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentTaskService taskService;
    public AgentController(AgentTaskService taskService) { this.taskService = taskService; }
    @PostMapping("/tasks")
    public ResponseEntity<TaskResponse> createTask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTaskRequest request) {
        RunCoordinator.RunAccepted a = taskService.create(jwt.getSubject(), request.input());
        return ResponseEntity.accepted().location(URI.create("/api/agent/tasks/" + a.taskId()))
                .cacheControl(CacheControl.noStore()).body(new TaskResponse(a.taskId(), a.runId(), a.sessionId(), a.status().name()));
    }
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<RunSnapshot> task(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(taskService.getTask(jwt.getSubject(), taskId));
    }
    @GetMapping("/tasks/{taskId}/steps")
    public ResponseEntity<List<StepResponse>> steps(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(taskService.getSteps(jwt.getSubject(), taskId).stream().map(StepResponse::new).toList());
    }
    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<RunSnapshot> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        RunCoordinator.CancelReply reply = taskService.cancel(jwt.getSubject(), taskId);
        return ResponseEntity.status(reply.accepted() ? 202 : 200).cacheControl(CacheControl.noStore()).body(reply.snapshot());
    }
    @GetMapping("/tasks/{taskId}/events")
    public ResponseEntity<Void> events(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        taskService.getTask(jwt.getSubject(), taskId);
        return ResponseEntity.status(410).cacheControl(CacheControl.noStore()).build();
    }
    public record CreateTaskRequest(@NotBlank @Size(max=8000) String input) { }
    public record TaskResponse(String taskId, String runId, String sessionId, String status) {
        public TaskResponse(String taskId, String sessionId, String status) { this(taskId, taskId, sessionId, status); }
    }
    public record StepResponse(int stepNo, String stepType, String toolName, String input, String output,
                               String status, long latencyMs, Integer promptTokens, Integer completionTokens,
                               String errorMessage, String decisionId, String callId, String errorCode, boolean terminal) {
        StepResponse(RunResultProjector.ProjectedStep s) { this(s.stepNo(), s.stepType(), s.name(), s.input(), s.output(), s.status(), s.latencyMs(), s.promptTokens(), s.completionTokens(), s.errorMessage(), s.decisionId(), s.callId(), s.errorCode(), s.terminal()); }
        public StepResponse(int no, String type, String tool, String status, String output, String error) { this(no,type,tool,null,output,status,0,null,null,error,null,null,null,false); }
    }
}
