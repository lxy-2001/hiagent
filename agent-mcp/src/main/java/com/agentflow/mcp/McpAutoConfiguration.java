package com.agentflow.mcp;

import com.agentflow.core.tool.ToolExecutionPolicy;
import com.agentflow.core.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import java.util.*;

@AutoConfiguration(beforeName="com.agentflow.tool.AgentToolAutoConfiguration",
        afterName="com.agentflow.tool.ToolPolicyAutoConfiguration")
@ConditionalOnProperty(prefix="agentflow.mcp",name="enabled",havingValue="true")
@ConditionalOnMissingBean(ToolRegistry.class)
@EnableConfigurationProperties(McpProperties.class)
public class McpAutoConfiguration {
    @Bean(destroyMethod="close")
    @ConditionalOnMissingBean(McpToolProvider.class)
    McpConnections mcpConnections(McpProperties properties,ObjectProvider<McpClientOperations> replacement) {
        List<McpClientOperations> custom=replacement.orderedStream().toList();
        if (!custom.isEmpty() && (custom.size()!=1 || properties.servers().size()!=1))
            throw new IllegalArgumentException("custom MCP client requires one explicitly configured server");
        Map<String,McpClientOperations> clients=new LinkedHashMap<>();
        try {
            for(var server:properties.servers()) clients.put(server.id(),custom.isEmpty()
                    ?new SdkMcpClientOperations(server,properties.requestTimeout()):custom.get(0));
            return new McpConnections(clients,custom.isEmpty());
        } catch(RuntimeException failure) {
            if(custom.isEmpty()) clients.values().forEach(McpClientOperations::close);
            throw failure;
        }
    }
    @Bean
    @ConditionalOnMissingBean(McpToolProvider.class)
    McpToolProvider mcpToolProvider(McpProperties properties,McpConnections connections,ToolExecutionPolicy policy) {
        try {return new McpToolProvider(properties,connections.clients(),policy);}
        catch(RuntimeException failure) {connections.close();throw failure;}
    }
    record McpConnections(Map<String,McpClientOperations> clients,boolean owned) implements AutoCloseable {
        McpConnections {clients=Map.copyOf(clients);}
        public void close() {if(owned) clients.values().forEach(McpClientOperations::close);}
    }
}
