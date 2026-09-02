package com.agentflow.web.autoconfigure;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.planner.TaskPlanner;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.web.DefaultAgentRuntime;
import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentSessionEntity;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskService;
import com.agentflow.web.agent.JpaStepRecorder;
import com.agentflow.web.agent.TaskEventPublisher;
import com.agentflow.web.auth.AuthController;
import com.agentflow.web.auth.AuthService;
import com.agentflow.web.auth.JwtService;
import com.agentflow.web.auth.SysUser;
import com.agentflow.web.chat.ChatController;
import com.agentflow.web.chat.ChatService;
import com.agentflow.web.config.SecurityConfig;
import com.agentflow.web.memory.InMemoryShortTermMemory;
import com.agentflow.web.planner.SimpleTaskPlanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@AutoConfigurationPackage(basePackageClasses = {AgentSessionEntity.class, SysUser.class})
@EnableConfigurationProperties(AgentFlowProperties.class)
@Import({
        AgentController.class,
        AgentTaskService.class,
        TaskEventPublisher.class,
        AuthController.class,
        AuthService.class,
        JwtService.class,
        ChatController.class,
        ChatService.class,
        SecurityConfig.class
})
public class AgentWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TaskPlanner.class)
    TaskPlanner taskPlanner() {
        return new SimpleTaskPlanner();
    }

    @Bean
    @ConditionalOnMissingBean(ShortTermMemory.class)
    ShortTermMemory shortTermMemory() {
        return new InMemoryShortTermMemory();
    }

    @Bean
    @ConditionalOnMissingBean(StepRecorder.class)
    StepRecorder stepRecorder(AgentStepRepository stepRepository) {
        return new JpaStepRecorder(stepRepository);
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntime.class)
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
