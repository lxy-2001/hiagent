package com.agentflow.web.approval;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

@RestController
@RequestMapping("/api/agent/tasks/{taskId}/approvals")
public class ApprovalController {
    private final ApprovalService service;
    public ApprovalController(ApprovalService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ApprovalList> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        uuid(taskId);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new ApprovalList(service.list(jwt.getSubject(), taskId)));
    }
    @GetMapping("/{approvalId}")
    public ResponseEntity<ApprovalSnapshot> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId,
                                               @PathVariable String approvalId) {
        uuid(taskId); uuid(approvalId);
        return response(service.get(jwt.getSubject(), taskId, approvalId));
    }
    @PostMapping("/{approvalId}/decision")
    public ResponseEntity<ApprovalSnapshot> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId,
            @PathVariable String approvalId, @RequestBody JsonNode body) {
        uuid(taskId); uuid(approvalId);
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("decision") || !body.get("decision").isString())
            throw new IllegalArgumentException("decision required");
        return response(service.decide(jwt.getSubject(), taskId, approvalId,
                ApprovalService.Decision.valueOf(body.get("decision").asString())));
    }
    private static void uuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("invalid identifier");
    }
    private static ResponseEntity<ApprovalSnapshot> response(ApprovalSnapshot value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
    public record ApprovalList(List<ApprovalSnapshot> items) { public ApprovalList { items = List.copyOf(items); } }
}
