package com.agentflow.mcp;

import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.tool.ToolArguments;
import java.util.List;
import java.util.Map;

/** SDK replacement boundary. Raw decoded maps retain every remote schema constraint. */
public interface McpClientOperations extends AutoCloseable {
    record Page(List<Map<String,Object>> tools, String nextCursor) {
        public Page { tools=List.copyOf(tools); }
    }
    void initialize(ToolExecutionControl control);
    Page listTools(String cursor,ToolExecutionControl control);
    Map<String,Object> call(String remoteName,ToolArguments arguments,ToolExecutionControl control);
    @Override void close();
}
