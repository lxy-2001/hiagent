package com.agentflow.web.conversation;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/sessions")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ConversationService.ConversationPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int limit) {
        return response(service.list(jwt.getSubject(), before, limit));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ConversationService.Conversation> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        return response(service.get(jwt.getSubject(), sessionId));
    }

    @GetMapping("/{sessionId}/turns")
    public ResponseEntity<ConversationService.TurnPage> turns(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") String afterSequence, @RequestParam(required = false) String untilSequence,
            @RequestParam(defaultValue = "20") int limit) {
        return response(service.turns(jwt.getSubject(), sessionId, afterSequence, untilSequence, limit));
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
