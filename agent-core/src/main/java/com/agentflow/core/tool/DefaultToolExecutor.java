package com.agentflow.core.tool;

import java.util.Objects;

/** Fixed-order registry lookup, schema validation, execution and result normalization. */
public final class DefaultToolExecutor implements ToolExecutor {
    private static final ToolResultNormalizer SAFETY_NORMALIZER = new DefaultToolResultNormalizer();
    private final ToolRegistry registry;
    private final ToolResultNormalizer normalizer;

    public DefaultToolExecutor(ToolRegistry registry, ToolResultNormalizer normalizer) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.normalizer = normalizer == null ? new DefaultToolResultNormalizer() : normalizer;
    }

    public DefaultToolExecutor(ToolRegistry registry) {
        this(registry, new DefaultToolResultNormalizer());
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(context, "context must not be null");
        ToolLookup lookup = registry.lookup(call.name());
        if (lookup == null || lookup.availability() == ToolAvailability.UNKNOWN) {
            return ToolResult.failure(call.name(), call.callId(), "UNKNOWN_TOOL", "tool is not registered");
        }
        if (lookup.availability() == ToolAvailability.DISABLED) {
            return ToolResult.failure(call.name(), call.callId(), "DISABLED_TOOL", "tool is disabled");
        }
        ValidationResult validation = lookup.registration().definition().schema().validate(call.arguments());
        if (!validation.valid()) {
            return ToolResult.failure(call.name(), call.callId(), "INVALID_TOOL_ARGUMENTS",
                    validation.violations().toString());
        }
        ToolResult raw;
        try {
            raw = lookup.registration().tool().execute(validation.arguments(), context);
        } catch (RuntimeException ex) {
            return ToolResult.failure(call.name(), call.callId(), "TOOL_ERROR",
                    DefaultToolResultNormalizer.sanitize(ex.getMessage()));
        }
        ToolResult normalized = normalizer.normalize(call, raw);
        if (raw != null && raw.retrievalPayload() != null && normalized != null
                && normalized.status() == ToolResultStatus.SUCCESS
                && !raw.retrievalPayload().equals(normalized.retrievalPayload())) {
            return ToolResult.failure(call.name(), call.callId(), "TOOL_RESULT_INVALID",
                    "normalizer changed retrieval evidence");
        }
        return SAFETY_NORMALIZER.normalize(call, normalized);
    }
}
