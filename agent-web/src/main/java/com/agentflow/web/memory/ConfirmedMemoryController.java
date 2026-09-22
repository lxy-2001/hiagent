package com.agentflow.web.memory;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.List;

@RestController
@RequestMapping("/api/agent/sessions/{sessionId}/memories")
public class ConfirmedMemoryController {
    private final ConfirmedMemoryService service;
    public ConfirmedMemoryController(ConfirmedMemoryService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<List<ConfirmedMemoryService.Slot>> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        validateSession(sessionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt.getSubject(), sessionId));
    }

    @PutMapping("/{key}")
    public ResponseEntity<ConfirmedMemoryService.Slot> put(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId,
                                                         @PathVariable String key, @RequestBody JsonNode body) {
        validateSession(sessionId);
        ConfirmedMemoryService.validateKey(key);
        MemoryWrite request = MemoryWrite.parse(body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.put(jwt.getSubject(), sessionId, key, request.value(), request.expectedVersion()));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<ConfirmedMemoryService.Slot> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId,
                                                            @PathVariable String key, @RequestParam String expectedVersion) {
        validateSession(sessionId);
        ConfirmedMemoryService.validateKey(key);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.delete(jwt.getSubject(), sessionId, key, parseVersion(expectedVersion)));
    }

    /** Strict parsing is local to this new request; Jackson coercion settings remain unchanged. */
    private record MemoryWrite(String value, long expectedVersion) {
        static MemoryWrite parse(JsonNode body) {
            if (body == null || !body.isObject() || body.size() != 2 || !body.has("value") || !body.has("expectedVersion")
                    || !body.get("value").isTextual() || !body.get("expectedVersion").isTextual()) {
                throw new IllegalArgumentException("invalid memory request");
            }
            return new MemoryWrite(body.get("value").asText(), parseVersion(body.get("expectedVersion").asText()));
        }
    }

    private static long parseVersion(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("invalid version");
        try { return Long.parseLong(value); }
        catch (NumberFormatException overflow) { throw new IllegalArgumentException("invalid version", overflow); }
    }

    private static void validateSession(String value) {
        if (value == null || value.isBlank() || value.length() > 36) throw new IllegalArgumentException("invalid sessionId");
    }
}
