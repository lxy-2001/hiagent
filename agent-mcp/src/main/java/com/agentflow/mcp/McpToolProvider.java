package com.agentflow.mcp;

import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.tool.*;
import java.time.Duration;
import java.util.*;

/** Publishes one complete immutable catalog, never a partially discovered registry. */
public final class McpToolProvider implements ToolProvider {
    public record DisabledTool(String serverId,String toolName,String reason) { }
    private final List<AgentTool> tools;
    private final List<DisabledTool> disabled;

    public McpToolProvider(McpProperties properties, Map<String,McpClientOperations> clients, ToolExecutionPolicy policy) {
        List<AgentTool> found=new ArrayList<>();List<DisabledTool> diagnostics=new ArrayList<>();
        McpSchemaMapper mapper=new McpSchemaMapper();
        if(properties.enabled()) for(var server:properties.servers()) {
            McpClientOperations client=Objects.requireNonNull(clients.get(server.id()),"configured client missing");
            var control=new ToolExecutionControl(CancellationSignal.NONE,TimeSource.system(),Duration.ofSeconds(15));
            client.initialize(control);
            String cursor=null;Set<String> cursors=new HashSet<>();Set<String> names=new HashSet<>();int count=0;
            for(int page=1;page<=4;page++) {
                control.checkActive();
                var result=client.listTools(cursor,control);
                count+=result.tools().size();if(count>32) limit();
                for(var remote:result.tools()) {
                    Object rawName=remote.get("name");
                    if(!(rawName instanceof String)) throw new IllegalArgumentException("MCP_PROTOCOL_ERROR");
                    String name=(String)rawName;
                    String internal=mapper.internalName(server.id(),name);
                    if(!names.add(name)) throw new IllegalArgumentException("MCP_DUPLICATE_TOOL");
                    if(!server.allowedTools().contains(name)) continue;
                    if(remote.get("outputSchema")!=null) {
                        diagnostics.add(new DisabledTool(server.id(),internal,"RESULT_SCHEMA_UNSUPPORTED"));continue;
                    }
                    ToolSchema schema;
                    try {schema=mapper.map(schema(remote.get("inputSchema")));}
                    catch(IllegalArgumentException ex) {diagnostics.add(new DisabledTool(server.id(),internal,"SCHEMA_UNSUPPORTED"));continue;}
                    Object description=remote.get("description");
                    String text=description instanceof String value?value:"Remote MCP tool";
                    text=new ContextTextPolicy().sanitizeInput(text);
                    if(text.isBlank()) text="Remote MCP tool";
                    if(text.length()>512) text=text.substring(0,Character.isHighSurrogate(text.charAt(511))?511:512);
                    var definition=new ToolDefinition(internal,text,policy.decide(internal).risk(),schema);
                    String version=ToolArgumentDigest.definitionVersion(definition,Map.of("serverId",server.id(),
                            "endpoint",server.url().toString(),"remoteName",name,"schema",remote.get("inputSchema")));
                    found.add(new McpAgentTool(definition,name,client,version));
                }
                cursor=result.nextCursor();
                if(cursor==null) break;
                if(page==4 || cursor.isEmpty() || !cursors.add(cursor)) limit();
            }
        }
        tools=List.copyOf(found);disabled=List.copyOf(diagnostics);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> schema(Object value) {
        if(!(value instanceof Map<?,?> map) || map.keySet().stream().anyMatch(k->!(k instanceof String)))
            throw new IllegalArgumentException("SCHEMA_UNSUPPORTED");
        return (Map<String,Object>)value;
    }
    private static void limit() {throw new IllegalArgumentException("MCP_DISCOVERY_LIMIT");}
    @Override public Collection<AgentTool> tools() {return tools;}
    public List<DisabledTool> disabledTools() {return disabled;}
}
