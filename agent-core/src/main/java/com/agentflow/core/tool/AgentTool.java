package com.agentflow.core.tool;

public interface AgentTool {

    String name();

    String description();

    RiskLevel riskLevel();

    ToolResult execute(String input, ToolContext context);
}
