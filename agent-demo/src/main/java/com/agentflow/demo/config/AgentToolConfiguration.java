package com.agentflow.demo.config;

import com.agentflow.core.tool.AgentTool;
import com.agentflow.tool.TextStatsTool;
import com.agentflow.tool.UppercaseTextTool;
import com.agentflow.demo.tool.CodeDraftTool;
import com.agentflow.demo.tool.InterfaceDraftTool;
import com.agentflow.demo.tool.SqlDraftTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit Demo composition of the reusable, deterministic local Tools. */
@Configuration(proxyBeanMethods = false)
public class AgentToolConfiguration {

    @Bean
    AgentTool uppercaseTextTool() {
        return new UppercaseTextTool();
    }

    @Bean
    AgentTool textStatsTool() {
        return new TextStatsTool();
    }

    @Bean
    AgentTool interfaceDraftTool() {
        return new InterfaceDraftTool();
    }

    @Bean
    AgentTool sqlDraftTool() {
        return new SqlDraftTool();
    }

    @Bean
    AgentTool codeDraftTool() {
        return new CodeDraftTool();
    }
}
