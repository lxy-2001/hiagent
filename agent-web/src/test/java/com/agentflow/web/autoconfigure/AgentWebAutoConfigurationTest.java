package com.agentflow.web.autoconfigure;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentRuntime;
import com.agentflow.core.chat.ChatCompletionRequest;
import com.agentflow.core.chat.ChatCompletionResponse;
import com.agentflow.core.chat.ChatModelClient;
import com.agentflow.core.memory.ShortTermMemory;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.planner.Plan;
import com.agentflow.core.planner.TaskPlanner;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.rag.AgentRagAutoConfiguration;
import com.agentflow.tool.InMemoryToolRegistry;
import com.agentflow.web.DefaultAgentRuntime;
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

import java.util.List;
import java.util.Set;
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
            assertThat(context).getBean(AgentRuntime.class).isInstanceOf(DefaultAgentRuntime.class);
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
        AgentRuntime custom = (request, eventSink) -> new AgentResult("custom", "answer", List.of());
        runner().withBean(AgentRuntime.class, () -> custom).run(context ->
                assertThat(context).getBean(AgentRuntime.class).isSameAs(custom));
    }

    @Test
    void failsWhenRagIsMissingInsteadOfInstallingNoop() {
        runnerWithoutRag().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isNotNull();
        });
    }

    private ApplicationContextRunner runner() {
        return baseRunner().withBean(RagRetriever.class,
                () -> (query, limit) -> List.of());
    }

    private ApplicationContextRunner runnerWithoutRag() {
        return baseRunner();
    }

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentWebAutoConfiguration.class,
                        AgentRagAutoConfiguration.class))
                .withUserConfiguration(TestDependencies.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestDependencies {

        @Bean
        AgentSessionRepository agentSessionRepository() {
            return mock(AgentSessionRepository.class);
        }

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
        Executor applicationTaskExecutor() {
            return Runnable::run;
        }

        @Bean
        AgentModelClient agentModelClient() {
            return prompt -> "model";
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
