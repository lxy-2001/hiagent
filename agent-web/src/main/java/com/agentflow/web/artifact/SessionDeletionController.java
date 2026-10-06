package com.agentflow.web.artifact;

import com.agentflow.core.context.ContextSessionException;
import com.agentflow.web.run.RunCoordinator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Harness-only lifecycle operation; acceptance precedes bounded storage cleanup. */
@RestController
@RequestMapping("/api/agent/sessions")
public class SessionDeletionController {
    private final ArtifactMaintenance maintenance;
    public SessionDeletionController(ArtifactMaintenance maintenance) { this.maintenance = maintenance; }
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<ArtifactMaintenance.SessionDeletion> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        try {
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(maintenance.delete(jwt.getSubject(), sessionId));
        } catch (ContextSessionException failure) {
            throw new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE", null);
        }
    }
}
