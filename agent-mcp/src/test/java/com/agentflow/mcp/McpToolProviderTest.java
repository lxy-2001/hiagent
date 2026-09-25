package com.agentflow.mcp;
import com.agentflow.core.tool.*;
import com.agentflow.core.runtime.ToolExecutionControl;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class McpToolProviderTest {
    static final McpProperties PROPERTIES=new McpProperties(true,List.of(new McpProperties.Server("demo",URI.create("https://example.com/mcp"),"",Set.of("project_info"))),Duration.ofSeconds(5));
    static Map<String,Object> tool(Map<String,Object> schema){return Map.of("name","project_info","description","fixture","inputSchema",schema);}
    static Map<String,Object> valid(){return Map.of("type","object","additionalProperties",false);}
    static final ToolExecutionPolicy POLICY=ToolExecutionPolicy.rules(Map.of("mcp.demo.project_info",new ToolPolicyDecision(ToolPolicyDecision.Action.ALLOW,RiskLevel.LOW,ToolPolicyDecision.Effect.READ_ONLY,"Read",Set.of())));
    static class Client implements McpClientOperations {
        final AtomicInteger lists=new AtomicInteger();final java.util.function.IntFunction<Page> page;
        Client(java.util.function.IntFunction<Page> page){this.page=page;}
        public void initialize(ToolExecutionControl control) { }
        public Page listTools(String cursor,ToolExecutionControl control){return page.apply(lists.incrementAndGet());}
        public Map<String,Object> call(String name,ToolArguments args,ToolExecutionControl control){return Map.of("content",List.of(Map.of("type","text","text","ok")));}
        public void close(){ }
    }
    @Test void validToolsRegisterButUnsupportedSchemasOnlyAppearInDiagnostics() {
        var provider=new McpToolProvider(PROPERTIES,Map.of("demo",new Client(i->new McpClientOperations.Page(List.of(tool(valid())),null))),POLICY);
        assertEquals(1,provider.tools().size());assertEquals("mcp.demo.project_info",provider.tools().iterator().next().definition().name());
        for(Object schema:List.of(Map.of("type","object","additionalProperties",false,"allOf",List.of()),false)) {
            var invalid=Map.<String,Object>of("name","project_info","inputSchema",schema);
            var disabled=new McpToolProvider(PROPERTIES,Map.of("demo",new Client(i->new McpClientOperations.Page(List.of(invalid),null))),POLICY);
            assertTrue(disabled.tools().isEmpty());assertEquals(1,disabled.disabledTools().size());
        }
    }
    @Test void repeatedCursorAndFifthPageFailWithoutPartialCatalog() {
        for(boolean repeated:List.of(true,false)) {
            var client=new Client(i->new McpClientOperations.Page(List.of(),repeated?"same":"cursor-"+i));
            assertThrows(IllegalArgumentException.class,()->new McpToolProvider(PROPERTIES,Map.of("demo",client),POLICY));
            assertTrue(client.lists.get()<=4);
        }
    }
    @Test void allDiscoveredToolsCountTowardLimitEvenOutsideAllowlist() {
        var tools=new ArrayList<Map<String,Object>>();for(int i=0;i<33;i++)tools.add(Map.of("name","other"+i,"inputSchema",valid()));
        assertThrows(IllegalArgumentException.class,()->new McpToolProvider(PROPERTIES,Map.of("demo",new Client(i->new McpClientOperations.Page(tools,null))),POLICY));
    }
    @Test void outputSchemaDisablesWithoutRegisteringPlaceholder() {
        var tool=new HashMap<>(tool(valid()));tool.put("outputSchema",Map.of("type","object"));
        var provider=new McpToolProvider(PROPERTIES,Map.of("demo",new Client(i->new McpClientOperations.Page(List.of(tool),null))),POLICY);
        assertTrue(provider.tools().isEmpty());assertEquals("RESULT_SCHEMA_UNSUPPORTED",provider.disabledTools().get(0).reason());
    }

    @Test void endpointAndRawSchemaAreBoundIntoDefinitionVersion() {
        var versions=new HashSet<String>();
        for(String path:List.of("/mcp","/other")) {
            var properties=new McpProperties(true,List.of(new McpProperties.Server("demo",URI.create("https://example.com"+path),"",Set.of("project_info"))),Duration.ofSeconds(5));
            var provider=new McpToolProvider(properties,Map.of("demo",new Client(n->new McpClientOperations.Page(List.of(tool(valid())),null))),POLICY);
            var registration=new ToolRegistration(provider.tools().iterator().next(),true);
            versions.add(PreparedToolCall.prepare("run","session","owner",registration,new ToolCall("call",registration.definition().name(),new ToolArguments(Map.of())),java.time.Instant.now()).toolDefinitionVersion());
        }
        assertEquals(2,versions.size());
    }
}
