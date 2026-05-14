package com.agentflow.web.autoconfigure;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.planner.TaskPlanner;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.web.DefaultAgentRuntime;
import com.agentflow.web.memory.InMemoryShortTermMemory;
import com.agentflow.web.planner.SimpleTaskPlanner;
import com.agentflow.web.step.NoopStepRecorder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AgentFlowProperties.class)
public class AgentWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TaskPlanner taskPlanner() {
        return new SimpleTaskPlanner();
    }

    @Bean
    @ConditionalOnMissingBean
    ShortTermMemory shortTermMemory() {
        return new InMemoryShortTermMemory();
    }

    @Bean
    @ConditionalOnMissingBean
    StepRecorder stepRecorder() {
        return new NoopStepRecorder();
    }

    @Bean
    @ConditionalOnMissingBean
    AgentRuntime agentRuntime(
            TaskPlanner taskPlanner,
            RagRetriever ragRetriever,
            ToolRegistry toolRegistry,
            AgentModelClient modelClient,
            StepRecorder stepRecorder,
            ShortTermMemory shortTermMemory,
            AgentFlowProperties properties
    ) {
        return new DefaultAgentRuntime(taskPlanner, ragRetriever, toolRegistry, modelClient, stepRecorder,
                shortTermMemory, properties.tools().maxSteps());
    }
}
