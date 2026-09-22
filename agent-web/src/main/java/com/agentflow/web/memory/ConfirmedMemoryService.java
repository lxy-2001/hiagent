package com.agentflow.web.memory;

import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.run.RunCoordinator;
import com.agentflow.web.support.Ids;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.context.ConfirmedMemory;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Owner-confirmed memory with session-row serialized compare-and-set writes. */
public class ConfirmedMemoryService {
    private final AgentSessionRepository sessions;
    private final ConfirmedMemoryRepository memories;
    private final ContextTextPolicy textPolicy;

    public ConfirmedMemoryService(AgentSessionRepository sessions, ConfirmedMemoryRepository memories, ContextTextPolicy textPolicy) {
        this.sessions = Objects.requireNonNull(sessions);
        this.memories = Objects.requireNonNull(memories);
        this.textPolicy = Objects.requireNonNull(textPolicy);
    }
    public record Slot(String sessionId, String key, String type, String state, String value, String version, String source, Instant updatedAt) { }
    public static final class VersionConflictException extends RuntimeException { }

    @Transactional(readOnly = true, timeout = 2)
    public List<Slot> get(String owner, String session) {
        if (sessions.findByIdAndUserId(session, owner).isEmpty()) throw new RunCoordinator.RunNotFoundException();
        List<ConfirmedMemoryEntity> found = memories.findBySessionId(session);
        return List.of(ConfirmedMemory.PREFERRED_LANGUAGE, ConfirmedMemory.PROJECT_STACK).stream()
                .map(key -> slot(session, key, found.stream().filter(value -> key.equals(value.getKey())).findFirst().orElse(null))).toList();
    }

    @Transactional(timeout = 2)
    public Slot put(String owner, String session, String key, String value, long expected) {
        validateKey(key);
        if (!textPolicy.isMemoryValueAllowed(value)) throw new IllegalArgumentException("invalid memory value");
        return write(owner, session, key, value, expected);
    }

    @Transactional(timeout = 2)
    public Slot delete(String owner, String session, String key, long expected) {
        validateKey(key);
        return write(owner, session, key, null, expected);
    }

    private Slot write(String owner, String session, String key, String value, long expected) {
        if (expected < 0) throw new IllegalArgumentException("invalid version");
        sessions.findOwnedForUpdate(session, owner).orElseThrow(RunCoordinator.RunNotFoundException::new);
        ConfirmedMemoryEntity entity = memories.findBySessionIdAndKey(session, key).orElse(null);
        long actual = entity == null ? 0 : entity.getVersion();
        if (expected != actual) throw new VersionConflictException();
        if (entity == null && value == null) return slot(session, key, null);
        if (entity != null && Objects.equals(entity.getValue(), value)) return slot(session, key, entity);
        if (actual == Long.MAX_VALUE) throw new RunCoordinator.RunUnavailableException("MEMORY_VERSION_EXHAUSTED", null);
        if (entity == null) entity = new ConfirmedMemoryEntity(Ids.newId(), session, key);
        entity.update(value, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        memories.saveAndFlush(entity);
        return slot(session, key, entity);
    }

    public static void validateKey(String key) {
        if (!ConfirmedMemory.PREFERRED_LANGUAGE.equals(key) && !ConfirmedMemory.PROJECT_STACK.equals(key)) {
            throw new IllegalArgumentException("unsupported memory key");
        }
    }

    private static Slot slot(String session, String key, ConfirmedMemoryEntity entity) {
        String type = ConfirmedMemory.PREFERRED_LANGUAGE.equals(key) ? "USER_PREFERENCE" : "PROJECT_FACT";
        return entity == null ? new Slot(session, key, type, "ABSENT", null, "0", null, null)
                : new Slot(session, key, type, entity.getState(), entity.getValue(), Long.toString(entity.getVersion()), entity.getSource(), entity.getUpdatedAt());
    }
}
