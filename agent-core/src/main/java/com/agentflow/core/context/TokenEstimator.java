package com.agentflow.core.context;

import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolDefinition;
import java.util.List;

public interface TokenEstimator {
    long estimateInput(List<ModelMessage> messages, List<ToolDefinition> tools);
    String version();
}
