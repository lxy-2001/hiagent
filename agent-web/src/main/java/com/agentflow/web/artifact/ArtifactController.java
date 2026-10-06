package com.agentflow.web.artifact;

import com.agentflow.core.artifact.ArtifactStore;
import com.agentflow.core.context.ContextSessionException;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.web.run.RunCoordinator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Objects;

/** Public ranges contain managed content only; ownership is checked before state disclosure. */
@RestController
@RequestMapping("/api/agent/artifacts")
public class ArtifactController {
    private final JdbcTemplate jdbc;
    private final ArtifactStore artifacts;
    private final RunCoordinator coordinator;
    private final Clock clock;
    public ArtifactController(JdbcTemplate jdbc, ArtifactStore artifacts, RunCoordinator coordinator, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.clock = Objects.requireNonNull(clock);
    }
    public record ArtifactRange(String artifactId, String state, String content,
            long actualOffsetBytes, long nextOffsetBytes, boolean eof, long savedBytes,
            boolean truncated, boolean redacted) { }

    @GetMapping("/{artifactId}")
    public ResponseEntity<ArtifactRange> read(@AuthenticationPrincipal Jwt jwt, @PathVariable String artifactId,
            @RequestParam(defaultValue = "0") long offsetBytes, @RequestParam(defaultValue = "32768") int maxBytes) {
        if (artifactId.isBlank() || artifactId.length() > 64 || offsetBytes < 0 || maxBytes < 1 || maxBytes > 65536) {
            throw new IllegalArgumentException("invalid artifact range");
        }
        var scopes = jdbc.query("select a.session_id,a.run_id from agent_artifact a "
                + "join agent_session s on s.id=a.session_id join agent_task t on t.id=a.run_id "
                + "left join agent_session_context c on c.session_id=s.id "
                + "where a.id=? and a.owner_id=? and s.user_id=? and t.user_id=? and t.session_id=s.id and c.deleted_at is null",
                (rs, row) -> new HistoryStore.Scope(jwt.getSubject(), rs.getString(1), rs.getString(2)),
                artifactId, jwt.getSubject(), jwt.getSubject(), jwt.getSubject());
        if (scopes.isEmpty()) throw new RunCoordinator.RunNotFoundException();
        HistoryStore.Scope scope = scopes.get(0);
        coordinator.getOwned(jwt.getSubject(), scope.runId());
        try {
            var range = artifacts.read(scope, artifactId, offsetBytes, maxBytes, 8192,
                    new HistoryStore.Io(() -> Thread.currentThread().isInterrupted(), clock.instant().plusSeconds(10)));
            var ref = range.artifact();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ArtifactRange(ref.id(),
                    ref.state().name(), range.content(), range.actualOffsetBytes(), range.nextOffsetBytes(), range.eof(),
                    ref.savedBytes(), ref.truncated(), ref.redacted()));
        } catch (ContextSessionException | UncheckedIOException failure) {
            throw new RunCoordinator.RunUnavailableException("ARTIFACT_STORAGE_FAILED", scope.runId());
        } catch (IllegalStateException failure) {
            switch (Objects.toString(failure.getMessage(), "")) {
                case "ARTIFACT_NOT_FOUND" -> throw new RunCoordinator.RunNotFoundException();
                case "INVALID_ARTIFACT_RANGE" -> throw new IllegalArgumentException("invalid artifact range");
                case "ARTIFACT_EVICTED", "ARTIFACT_MISSING" -> throw new GoneException(failure.getMessage());
                default -> throw new RunCoordinator.RunUnavailableException("ARTIFACT_STORAGE_FAILED", scope.runId());
            }
        }
    }
    public static final class GoneException extends RuntimeException {
        private final String code;
        GoneException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
