package com.agentflow.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryToolRegistryTest {

    @Test
    void registersToolsByName() {
        var registry = new InMemoryToolRegistry(List.of(new TestTool()));

        assertTrue(registry.findEnabled("test-tool").isPresent());
        assertTrue(registry.enabledToolNames().contains("test-tool"));
    }

    private static class TestTool implements AgentTool {

        @Override
        public String name() {
            return "test-tool";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.LOW;
        }

        @Override
        public ToolResult execute(String input, ToolContext context) {
            return new ToolResult(name(), "ok");
        }
    }
}
