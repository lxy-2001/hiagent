package com.agentflow.web.context;

import com.agentflow.web.run.RunCoordinator;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/agent/sessions/{sessionId}")
public class ContextHistoryController {
    private final ContextHistoryService service;
    public ContextHistoryController(ContextHistoryService service) { this.service = service; }

    @GetMapping("/messages")
    public ResponseEntity<ContextHistoryService.HistoryPage> messages(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId, @RequestParam(defaultValue = "0") String afterSequence,
            @RequestParam(required = false) String untilSequence, @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read(sessionId,
                () -> service.messages(jwt.getSubject(), sessionId, afterSequence, untilSequence, limit)));
    }

    @GetMapping("/context")
    public ResponseEntity<ContextHistoryService.ContextSnapshot> context(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read(sessionId,
                () -> service.context(jwt.getSubject(), sessionId)));
    }

    @PostMapping("/touch")
    public ResponseEntity<Void> touch(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        read(sessionId, () -> { service.touch(jwt.getSubject(), sessionId); return null; });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> T read(String sessionId, Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | PersistenceException failure) {
            // Catch outside the transactional proxy, including failures reported during commit.
            throw new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE", sessionId);
        }
    }
}
