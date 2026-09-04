package com.agentflow.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolSchema;

import java.util.Map;
import java.util.Set;

/** Deterministic text metrics Tool with no I/O or external dependencies. */
public final class TextStatsTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "text-stats", "Count characters, words and lines", RiskLevel.LOW,
            new ToolSchema(Map.of("text", ParameterSpec.requiredString(4096)), Set.of("text"), false));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolContext context) {
        String text = (String) arguments.values().get("text");
        int chars = text.length();
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        int lines = text.isEmpty() ? 0 : text.split("\\R", -1).length;
        return ToolResult.success(DEFINITION.name(), "chars=%d, words=%d, lines=%d".formatted(chars, words, lines));
    }
}
