package com.agentflow.spring.autoconfigure;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.DefaultAgentRuntime;
import com.agentflow.core.memory.InMemoryShortTermMemory;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import com.agentflow.core.planner.SimpleTaskPlanner;
import com.agentflow.core.planner.TaskPlanner;
import com.agentflow.core.rag.NoopRagRetriever;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.step.NoopStepRecorder;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.InMemoryToolRegistry;
import com.agentflow.core.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(AgentFlowProperties.class)
public class AgentFlowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenAiCompatibleModelClient openAiCompatibleModelClient(AgentFlowProperties properties, RestClient.Builder builder) {
        return new OpenAiCompatibleModelClient(properties, builder);
    }

    @Bean
    @ConditionalOnMissingBean(AgentModelClient.class)
    AgentModelClient agentModelClient(OpenAiCompatibleModelClient client) {
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient embeddingClient(OpenAiCompatibleModelClient client) {
        return client;
    }

    @Bean
    @ConditionalOnMissingBean
    TaskPlanner taskPlanner() {
        return new SimpleTaskPlanner();
    }

    @Bean
    @ConditionalOnMissingBean
    ToolRegistry toolRegistry(List<AgentTool> tools) {
        return new InMemoryToolRegistry(tools);
    }

    @Bean
    @ConditionalOnMissingBean
    ShortTermMemory shortTermMemory() {
        return new InMemoryShortTermMemory();
    }

    @Bean
    @ConditionalOnMissingBean
    RagRetriever ragRetriever() {
        return new NoopRagRetriever();
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
