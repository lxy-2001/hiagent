package com.agentflow.mcp;

import com.agentflow.core.tool.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Forwards one original call, with no retry and no synthesized retrieval evidence. */
public final class McpAgentTool implements AgentTool {
    private final ToolDefinition definition;
    private final String remoteName;
    private final McpClientOperations client;
    private final String definitionVersion;

    public McpAgentTool(ToolDefinition definition, String remoteName, McpClientOperations client) {
        this(definition,remoteName,client,ToolArgumentDigest.definitionVersion(definition));
    }
    McpAgentTool(ToolDefinition definition,String remoteName,McpClientOperations client,String version) {
        this.definitionVersion=Objects.requireNonNull(version);
        this.definition=Objects.requireNonNull(definition);
        this.remoteName=Objects.requireNonNull(remoteName);
        this.client=Objects.requireNonNull(client);
    }
    @Override public String definitionVersion() { return definitionVersion; }
    @Override public ToolDefinition definition() {return definition;}
    @Override public ToolResult execute(ToolArguments arguments, ToolContext context) {
        try {
            context.control().checkActive();
            Map<String,Object> result=client.call(remoteName,arguments,context.control());
            if(result.containsKey("isError") && !(result.get("isError") instanceof Boolean)) return failure("MCP_PROTOCOL_ERROR");
            if(result.get("structuredContent")!=null) return failure("MCP_RESULT_UNSUPPORTED");
            if(!(result.get("content") instanceof List<?> content)) return failure("MCP_PROTOCOL_ERROR");
            StringBuilder text=new StringBuilder();
            boolean first=true;
            for(Object block:content) {
                if(!(block instanceof Map<?,?> entry) || !"text".equals(entry.get("type"))) return failure("MCP_RESULT_UNSUPPORTED");
                if(!(entry.get("text") instanceof String value)) return failure("MCP_PROTOCOL_ERROR");
                if(!first) text.append('\n');
                first=false;
                text.append(value);
                if(text.length()>DefaultToolResultNormalizer.MAX_OUTPUT_CHARS) return failure("TOOL_RESULT_TOO_LARGE");
            }
            if(Boolean.TRUE.equals(result.get("isError"))) return failure("MCP_TOOL_ERROR");
            if(text.toString().isBlank()) return failure("TOOL_RESULT_INVALID");
            return ToolResult.success(definition.name(),text.toString());
        } catch(McpOperationException ex) {return failure(ex.code());}
        catch(java.util.concurrent.CancellationException ex) {return failure("CANCELLED");}
        catch(com.agentflow.core.runtime.ToolExecutionControl.ExecutionTimedOutException ex) {return failure("MCP_TIMEOUT");}
        catch(RuntimeException ex) {return failure("MCP_PROTOCOL_ERROR");}
    }
    private ToolResult failure(String code) {return ToolResult.failure(definition.name(),null,code,"MCP call failed");}
}
