package com.agentflow.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ToolProvider;

import java.util.Collection;
import java.util.List;

public class McpToolProvider implements ToolProvider {

    @Override
    public Collection<AgentTool> tools() {
        return List.of();
    }
}
