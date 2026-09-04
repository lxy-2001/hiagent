package com.agentflow.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolAvailability;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolLookup;
import com.agentflow.core.tool.ToolRegistration;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryToolRegistryTest {

    @Test
    void preservesRegistrationOrderAndExposesOnlyEnabledDefinitions() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry(List.of(tool("first"), tool("second")));
        registry.register(new ToolRegistration(tool("disabled"), false));

        assertEquals(List.of("first", "second"), registry.enabledDefinitions().stream()
                .map(ToolDefinition::name).toList());
        assertEquals(ToolAvailability.ENABLED, registry.lookup("first").availability());
        assertEquals(ToolAvailability.DISABLED, registry.lookup("disabled").availability());
        assertEquals(ToolAvailability.UNKNOWN, registry.lookup("missing").availability());
        assertEquals(Set.of("first", "second"), registry.enabledToolNames());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.enabledDefinitions().clear());
    }

    @Test
    void rejectsDuplicateWithoutReplacingExistingRegistration() {
        AgentTool original = tool("same");
        InMemoryToolRegistry registry = new InMemoryToolRegistry(List.of(original));

        assertThrows(IllegalArgumentException.class, () -> registry.register(new ToolRegistration(tool("same"), true)));
        assertTrue(registry.lookup("same").registration().tool() == original);
    }

    @Test
    void keepsCanonicalDefinitionWhenImplementationChangesAfterRegistration() {
        ToolDefinition initial = new ToolDefinition("stable", "initial description", RiskLevel.LOW,
                new ToolSchema(Map.of(), Set.of(), false));
        ToolDefinition changed = new ToolDefinition("changed", "changed description", RiskLevel.HIGH,
                new ToolSchema(Map.of(), Set.of(), false));
        final ToolDefinition[] current = {initial};
        AgentTool mutable = new AgentTool() {
            @Override public ToolDefinition definition() { return current[0]; }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) {
                return ToolResult.success("stable", "ok");
            }
        };

        InMemoryToolRegistry registry = new InMemoryToolRegistry(List.of(mutable));
        current[0] = changed;

        assertEquals(List.of(initial), registry.enabledDefinitions());
        assertEquals(ToolAvailability.ENABLED, registry.lookup("stable").availability());
        assertEquals(ToolAvailability.UNKNOWN, registry.lookup("changed").availability());
    }

    @Test
    void rejectsNullOrInvalidToolRegistration() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry(List.of());
        assertThrows(NullPointerException.class, () -> registry.register((ToolRegistration) null));
        AgentTool invalid = new AgentTool() {
            @Override public ToolDefinition definition() { return null; }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) { return null; }
        };
        assertThrows(NullPointerException.class, () -> registry.register(new ToolRegistration(invalid, true)));
    }

    private static AgentTool tool(String name) {
        ToolDefinition definition = new ToolDefinition(name, name + " description", RiskLevel.LOW,
                new ToolSchema(Map.of("text", ParameterSpec.requiredString(64)), Set.of("text"), false));
        return new AgentTool() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) {
                return ToolResult.success(name, String.valueOf(arguments.values().get("text")));
            }
        };
    }
}
