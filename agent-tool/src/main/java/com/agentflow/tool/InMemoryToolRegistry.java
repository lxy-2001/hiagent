package com.agentflow.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ToolAvailability;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolLookup;
import com.agentflow.core.tool.ToolRegistration;
import com.agentflow.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Thread-safe, deterministic in-memory registry used by the starter and offline demos. */
public final class InMemoryToolRegistry implements ToolRegistry {
    private final Map<String, ToolRegistration> registrations = new LinkedHashMap<>();

    public InMemoryToolRegistry() {
    }

    public InMemoryToolRegistry(Collection<? extends AgentTool> tools) {
        Objects.requireNonNull(tools, "tools must not be null");
        tools.forEach(this::register);
    }

    @Override
    public synchronized void register(ToolRegistration registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        AgentTool tool = registration.tool();
        ToolDefinition definition = registration.definition();
        ToolDefinition implementationDefinition = tool.definition();
        if (implementationDefinition == null || !implementationDefinition.equals(definition)) {
            throw new IllegalArgumentException("tool definition is inconsistent");
        }
        String name = definition.name();
        if (registrations.containsKey(name)) {
            throw new IllegalArgumentException("DUPLICATE_TOOL: " + name);
        }
        if (registrations.size() >= 128) {
            throw new IllegalArgumentException("TOOL_CATALOG_LIMIT");
        }
        registrations.put(name, registration);
    }

    @Override
    public synchronized ToolLookup lookup(String name) {
        if (name == null || name.isBlank()) {
            return ToolLookup.unknown();
        }
        ToolRegistration registration = registrations.get(name);
        if (registration == null) {
            return ToolLookup.unknown();
        }
        return new ToolLookup(registration.enabled() ? ToolAvailability.ENABLED : ToolAvailability.DISABLED,
                registration);
    }

    @Override
    public synchronized List<ToolDefinition> enabledDefinitions() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (ToolRegistration registration : registrations.values()) {
            if (registration.enabled()) {
                definitions.add(registration.definition());
            }
        }
        return Collections.unmodifiableList(definitions);
    }

    /** Snapshot of all registrations, including disabled entries, for diagnostics/tests. */
    public synchronized List<ToolRegistration> registrations() {
        return List.copyOf(registrations.values());
    }
}
