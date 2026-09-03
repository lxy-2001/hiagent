package com.agentflow.core.tool;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ToolRegistry {

    default void register(AgentTool tool) {
        if (tool == null) {
            throw new NullPointerException("tool must not be null");
        }
        register(new ToolRegistration(tool, true));
    }

    void register(ToolRegistration registration);

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
        return Collections.unmodifiableSet(names);
    }
}
