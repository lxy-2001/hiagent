package com.agentflow.tool;

import com.agentflow.core.artifact.ArtifactStore;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.tool.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Reads a bounded UTF-8 byte range; ownership comes only from the runtime. */
public final class ReadArtifactTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition("read-artifact",
            "Read saved tool content by artifactId and byte offset. Follow nextOffsetBytes for more.", RiskLevel.LOW,
            new ToolSchema(Map.of("artifactId", ParameterSpec.requiredString(36),
                    "offsetBytes", new ParameterSpec(ValueType.INTEGER, false, false, "UTF-8 byte offset", null, null, 0L, Long.MAX_VALUE, null, null, Set.of(), null),
                    "maxBytes", new ParameterSpec(ValueType.INTEGER, false, false, "Read up to 65536 bytes", null, null, 1L, 65536L, null, null, Set.of(), null)), Set.of("artifactId"), false));
    private final ArtifactStore store;
    private final ObjectMapper json;
    public ReadArtifactTool(ArtifactStore store, ObjectMapper json) { this.store=store; this.json=json; }
    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public ToolResult execute(ToolArguments args, ToolContext context) {
        try {
            context.control().checkActive();
            String id = (String) args.values().get("artifactId");
            long offset = number(args.values().getOrDefault("offsetBytes", 0L));
            long size = number(args.values().getOrDefault("maxBytes", 32768));
            if (id == null || id.isBlank() || offset < 0 || size < 1 || size > 65536) throw new IllegalArgumentException("INVALID_ARTIFACT_RANGE");
            var scope = new HistoryStore.Scope(context.userId(), context.sessionId(), context.taskId());
            var io = new HistoryStore.Io(context.control().cancellationSignal(), Instant.now().plus(context.control().remainingTime()));
            var range = store.read(scope, id, offset, (int)size, 8192, io);
            var ref = range.artifact();
            String output = json.writeValueAsString(Map.of("artifactId", ref.id(), "state", ref.state().name(),
                    "content", range.content(), "actualOffsetBytes", range.actualOffsetBytes(),
                    "nextOffsetBytes", range.nextOffsetBytes(), "eof", range.eof(), "savedBytes", ref.savedBytes(),
                    "truncated", ref.truncated(), "redacted", ref.redacted()));
            return ToolResult.success(DEFINITION.name(), output);
        } catch (java.util.concurrent.CancellationException | com.agentflow.core.runtime.ToolExecutionControl.ExecutionTimedOutException e) { throw e; }
        catch (RuntimeException e) {
            String code = e instanceof IllegalArgumentException ? "INVALID_ARTIFACT_RANGE" : "ARTIFACT_UNAVAILABLE";
            return ToolResult.failure(DEFINITION.name(), null, code, e.getMessage());
        }
    }
    private static long number(Object value) { if (!(value instanceof Number n) || n.doubleValue() != n.longValue()) throw new IllegalArgumentException("INVALID_ARTIFACT_RANGE"); return n.longValue(); }
}
