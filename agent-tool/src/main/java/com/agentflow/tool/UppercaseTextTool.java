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

/** Deterministic, side-effect-free demonstration Tool. */
public final class UppercaseTextTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "uppercase-text", "Convert text to upper case", RiskLevel.LOW,
            new ToolSchema(Map.of("text", ParameterSpec.requiredString(4096)), Set.of("text"), false));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolContext context) {
        String text = (String) arguments.values().get("text");
        return ToolResult.success(DEFINITION.name(), text.toUpperCase(java.util.Locale.ROOT));
    }
}
