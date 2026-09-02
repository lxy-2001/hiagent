package com.agentflow.demo;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.EmbeddingClient;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.demo.knowledge.KnowledgeChunkRepository;
import com.agentflow.demo.knowledge.KnowledgeController;
import com.agentflow.demo.knowledge.KnowledgeDocumentRepository;
import com.agentflow.demo.knowledge.KnowledgeRagRetriever;
import com.agentflow.demo.knowledge.KnowledgeService;
import com.agentflow.demo.tool.AgentToolRepository;
import com.agentflow.web.DefaultAgentRuntime;
import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.agent.AgentTaskService;
import com.agentflow.web.agent.JpaStepRecorder;
import com.agentflow.web.auth.AuthController;
import com.agentflow.web.auth.AuthRefreshTokenRepository;
import com.agentflow.web.auth.AuthService;
import com.agentflow.web.auth.SysUserRepository;
import com.agentflow.web.chat.ChatController;
import com.agentflow.web.chat.ChatService;
import com.agentflow.web.memory.InMemoryShortTermMemory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ActiveProfiles("test")
@SpringBootTest(classes = AgentFlowDemoContextTest.TestApplication.class)
class AgentFlowDemoContextTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void loadsCompleteDemoAssemblyWithoutMockingApplicationServices() {
        assertThat(context.getBeansOfType(AgentController.class)).hasSize(1);
        assertThat(context.getBeansOfType(AuthController.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChatController.class)).hasSize(1);
        assertThat(context.getBeansOfType(KnowledgeController.class)).hasSize(1);

        assertThat(context.getBeansOfType(AgentTaskService.class)).hasSize(1);
        assertThat(context.getBeansOfType(AuthService.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChatService.class)).hasSize(1);
        assertThat(context.getBeansOfType(KnowledgeService.class)).hasSize(1);

        assertThat(context.getBeansOfType(AgentSessionRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(AgentTaskRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(AgentStepRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(SysUserRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(AuthRefreshTokenRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(KnowledgeDocumentRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(KnowledgeChunkRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(AgentToolRepository.class)).hasSize(1);

        assertThat(context.getBeansOfType(AgentRuntime.class)).hasSize(1);
        assertThat(context.getBean(AgentRuntime.class)).isInstanceOf(DefaultAgentRuntime.class);
        assertThat(context.getBeansOfType(AgentModelClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(ChatModelClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(EmbeddingClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(ToolRegistry.class)).hasSize(1);
        assertThat(context.getBeansOfType(RagRetriever.class)).hasSize(1);
        assertThat(context.getBeansOfType(StepRecorder.class)).hasSize(1);
        assertThat(context.getBeansOfType(ShortTermMemory.class)).hasSize(1);

        assertThat(context.getBean(AgentModelClient.class).getClass().getSimpleName())
                .isEqualTo("OpenAiAgentModelClient");
        assertThat(context.getBean(ChatModelClient.class).getClass().getSimpleName())
                .isEqualTo("OpenAiChatModelClient");
        assertThat(context.getBean(EmbeddingClient.class).getClass().getSimpleName())
                .isEqualTo("OpenAiEmbeddingClient");
        assertThat(context.getBean(ToolRegistry.class).getClass().getSimpleName())
                .isEqualTo("InMemoryToolRegistry");
        assertThat(context.getBean(RagRetriever.class)).isInstanceOf(KnowledgeRagRetriever.class);
        assertThat(context.getBean(StepRecorder.class)).isInstanceOf(JpaStepRecorder.class);
        assertThat(context.getBean(ShortTermMemory.class)).isInstanceOf(InMemoryShortTermMemory.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(
            basePackages = "com.agentflow.demo",
            excludeFilters = {
                    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                            classes = AgentFlowDemoApplication.class)
            })
    @Import(TestExternalBoundaryConfig.class)
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestExternalBoundaryConfig {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
