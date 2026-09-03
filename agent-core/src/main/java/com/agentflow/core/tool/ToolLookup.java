package com.agentflow.core.tool;

import java.util.Objects;

public record ToolLookup(ToolAvailability availability, ToolRegistration registration) {
    public ToolLookup {
        Objects.requireNonNull(availability, "availability must not be null");
        if (availability == ToolAvailability.UNKNOWN && registration != null) {
            throw new IllegalArgumentException("unknown tool cannot have a registration");
        }
        if (availability != ToolAvailability.UNKNOWN && registration == null) {
            throw new IllegalArgumentException("known tool must have a registration");
        }
    }

    public static ToolLookup unknown() {
        return new ToolLookup(ToolAvailability.UNKNOWN, null);
    }
}
