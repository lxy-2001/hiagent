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
    @ConditionalOnMissingBean
    ToolRegistry toolRegistry(List<AgentTool> tools) {
        return new InMemoryToolRegistry(tools);
    }
}
