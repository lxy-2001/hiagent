package com.agentflow.web.autoconfigure;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatCompletionResponse;
import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.planner.Plan;
import com.agentflow.core.planner.TaskPlanner;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.tool.InMemoryToolRegistry;
import com.agentflow.web.agent.AgentSessionEntity;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentStepEntity;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskEntity;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.agent.JpaStepRecorder;
import com.agentflow.web.auth.AuthRefreshToken;
import com.agentflow.web.auth.AuthRefreshTokenRepository;
import com.agentflow.web.auth.SysUser;
import com.agentflow.web.auth.SysUserRepository;
import com.agentflow.web.config.SecurityConfig;
import com.agentflow.web.memory.InMemoryShortTermMemory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentWebAutoConfigurationTest {

    @Test
    void suppliesUniqueRuntimeSupportingDefaults() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(TaskPlanner.class);
            assertThat(context).hasSingleBean(ShortTermMemory.class);
            assertThat(context).hasSingleBean(StepRecorder.class);
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).getBean(AgentRuntime.class).isInstanceOf(com.agentflow.core.runtime.DefaultAgentRuntime.class);
            assertThat(context).getBean(ShortTermMemory.class).isInstanceOf(InMemoryShortTermMemory.class);
            assertThat(context).getBean(StepRecorder.class).isInstanceOf(JpaStepRecorder.class);
            assertThat(context.getBeansOfType(StepRecorder.class).keySet()).doesNotContain("noopStepRecorder");
        });
    }

    @Test
    void allowsApplicationPlannerOverride() {
        TaskPlanner custom = (input, tools) -> new Plan("", List.of());
        runner().withBean(TaskPlanner.class, () -> custom).run(context ->
                assertThat(context).getBean(TaskPlanner.class).isSameAs(custom));
    }

    @Test
    void allowsApplicationMemoryOverride() {
        ShortTermMemory custom = new ShortTermMemory() {
            @Override
            public void appendUserMessage(String sessionId, String message) {
            }

            @Override
            public void appendAssistantMessage(String sessionId, String message) {
            }

            @Override
            public List<String> recentMessages(String sessionId, int limit) {
                return List.of();
            }
        };
        runner().withBean(ShortTermMemory.class, () -> custom).run(context ->
                assertThat(context).getBean(ShortTermMemory.class).isSameAs(custom));
    }

    @Test
    void allowsApplicationRecorderOverride() {
        StepRecorder custom = step -> {
        };
        runner().withBean(StepRecorder.class, () -> custom).run(context ->
                assertThat(context).getBean(StepRecorder.class).isSameAs(custom));
    }

    @Test
    void allowsApplicationRuntimeOverride() {
        AgentRuntime custom = (request, eventSink, options) -> new AgentResult("custom", "answer", List.of());
        runner().withBean(AgentRuntime.class, () -> custom).run(context ->
                assertThat(context).getBean(AgentRuntime.class).isSameAs(custom));
    }

    @Test
    void doesNotRequireRagForCoreRuntimeAssembly() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(com.agentflow.core.rag.RagRetriever.class);
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(com.agentflow.tool.ToolPolicyAutoConfiguration.class, AgentWebAutoConfiguration.class))
                .withPropertyValues("agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32")
                .withUserConfiguration(TestDependencies.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestDependencies {
        @Bean com.agentflow.web.memory.ConfirmedMemoryRepository memories() { return mock(com.agentflow.web.memory.ConfirmedMemoryRepository.class); }

        @Bean
        AgentSessionRepository agentSessionRepository() {
            return mock(AgentSessionRepository.class);
        }

        @Bean
        com.agentflow.web.approval.ToolInvocationRepository invocations() { return mock(com.agentflow.web.approval.ToolInvocationRepository.class); }

        @Bean
        AgentTaskRepository agentTaskRepository() {
            return mock(AgentTaskRepository.class);
        }

        @Bean
        AgentStepRepository agentStepRepository() {
            return mock(AgentStepRepository.class);
        }

        @Bean
        SysUserRepository sysUserRepository() {
            return mock(SysUserRepository.class);
        }

        @Bean
        AuthRefreshTokenRepository authRefreshTokenRepository() {
            return mock(AuthRefreshTokenRepository.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        EntityManager entityManager() { return mock(EntityManager.class); }

        @Bean
        Executor applicationTaskExecutor() {
            return Runnable::run;
        }

        @Bean
        AgentModelClient agentModelClient() {
            return request -> new FinalAnswerDecision("test-decision", "model", TokenUsage.empty());
        }

        @Bean
        ChatModelClient chatModelClient() {
            return new ChatModelClient() {
                @Override
                public ChatCompletionResponse complete(ChatCompletionRequest request) {
                    return null;
                }

                @Override
                public ChatCompletionResponse stream(ChatCompletionRequest request,
                                                       java.util.function.Consumer<String> deltaConsumer) {
                    return null;
                }
            };
        }

        @Bean
        ToolRegistry toolRegistry() {
            return new InMemoryToolRegistry(List.of());
        }
    }
}
