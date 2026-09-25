package com.agentflow.core.tool;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Validated call and fixed implementation; raw arguments stay in the active stack. */
public record PreparedToolCall(String runId, String sessionId, String ownerId, String callId,
        ToolRegistration registration, ToolCall call, String toolDefinitionVersion,
        String argumentsDigest, Instant createdAt) {
    public PreparedToolCall {
        for (String id : new String[]{runId,sessionId,ownerId,callId})
            if (id == null || id.isBlank()) throw new IllegalArgumentException("blank call identity");
        Objects.requireNonNull(registration,"registration"); Objects.requireNonNull(call,"call");
        Objects.requireNonNull(createdAt,"createdAt");
        if (!callId.equals(call.callId()) || !registration.definition().name().equals(call.name())
                || !registration.enabled() || !registration.definition().schema().validate(call.arguments()).valid())
            throw new IllegalArgumentException("invalid prepared binding");
        if (!ToolArgumentDigest.definitionVersion(registration.definition()).equals(toolDefinitionVersion)
                || !ToolArgumentDigest.digest(call.arguments()).equals(argumentsDigest))
            throw new IllegalArgumentException("invalid prepared fingerprint");
        createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
    }
    public static PreparedToolCall prepare(String runId,String sessionId,String ownerId,
            ToolRegistration registration,ToolCall call,Instant createdAt) {
        return new PreparedToolCall(runId,sessionId,ownerId,call.callId(),registration,call,
                ToolArgumentDigest.definitionVersion(registration.definition()),ToolArgumentDigest.digest(call.arguments()),createdAt);
    }
    @Override public String toString() { return "PreparedToolCall[callId=" + callId + "]"; }
}
