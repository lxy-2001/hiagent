package com.agentflow.core.tool;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ToolRegistry {

    void register(AgentTool tool);

    default void register(ToolRegistration registration) {
        if (registration == null) {
            throw new NullPointerException("registration must not be null");
        }
        register(registration.tool());
    }

    ToolLookup lookup(String name);

    List<ToolDefinition> enabledDefinitions();

    default Optional<AgentTool> findEnabled(String name) {
        ToolLookup lookup = lookup(name);
        return lookup.availability() == ToolAvailability.ENABLED
                ? Optional.of(lookup.registration().tool()) : Optional.empty();
    }

    default Set<String> enabledToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDefinition definition : enabledDefinitions()) {
            names.add(definition.name());
        }
        return Set.copyOf(names);
    }
}
