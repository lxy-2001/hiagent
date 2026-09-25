package com.agentflow.tool;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class AgentToolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    ToolRegistry toolRegistry(List<AgentTool> tools, List<com.agentflow.core.tool.ToolProvider> providers) {
        if (tools.stream().anyMatch(tool -> tool.definition().name().startsWith("mcp.")))
            throw new IllegalArgumentException("mcp prefix is reserved for remote providers");
        InMemoryToolRegistry registry = new InMemoryToolRegistry(tools);
        providers.forEach(provider -> provider.tools().forEach(registry::register));
        return registry;
    }
}
