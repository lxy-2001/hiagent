package com.agentflow.web;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.tool.InMemoryToolRegistry;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.auth.AuthRefreshTokenRepository;
import com.agentflow.web.auth.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
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

class AgentWebRuntimeAssemblyTest {

    @Test
    void coreRuntimeIsTheOnlyRuntimeAndRagIsOptional() {
        runner().withBean(AgentModelClient.class, () ->
                request -> new FinalAnswerDecision("default", "answer", TokenUsage.empty()))
                .withBean(ToolRegistry.class, () -> new InMemoryToolRegistry(List.of()))
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context.getBean(AgentRuntime.class))
                    .isInstanceOf(com.agentflow.core.runtime.DefaultAgentRuntime.class);
            assertThat(context).doesNotHaveBean(com.agentflow.core.rag.RagRetriever.class);
        });
    }

    @Test
    void customPortsAreUsedByTheCoreRuntimeAssembly() {
        AgentModelClient model = request ->
                new FinalAnswerDecision("custom", "answer", TokenUsage.empty());
        ToolRegistry registry = new InMemoryToolRegistry(List.of());

        runner().withBean(AgentModelClient.class, () -> model)
                .withBean(ToolRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context.getBean(AgentModelClient.class)).isSameAs(model);
                    assertThat(context.getBean(ToolRegistry.class)).isSameAs(registry);
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentWebAutoConfiguration.class))
                .withPropertyValues("agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32")
                .withUserConfiguration(Dependencies.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class Dependencies {
        @Bean AgentSessionRepository sessions() { return mock(AgentSessionRepository.class); }
        @Bean AgentTaskRepository tasks() { return mock(AgentTaskRepository.class); }
        @Bean AgentStepRepository steps() { return mock(AgentStepRepository.class); }
        @Bean SysUserRepository users() { return mock(SysUserRepository.class); }
        @Bean AuthRefreshTokenRepository tokens() { return mock(AuthRefreshTokenRepository.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean EntityManager entityManager() { return mock(EntityManager.class); }
        @Bean Executor executor() { return Runnable::run; }
        @Bean com.agentflow.core.chat.ChatModelClient chat() {
            return new com.agentflow.core.chat.ChatModelClient() {
                @Override public com.agentflow.core.chat.ChatCompletionResponse complete(
                        com.agentflow.core.chat.ChatCompletionRequest request) {
                    return new com.agentflow.core.chat.ChatCompletionResponse(
                            "test", "test-model", "answer", TokenUsage.empty(), true);
                }
                @Override public com.agentflow.core.chat.ChatCompletionResponse stream(
                        com.agentflow.core.chat.ChatCompletionRequest request,
                        java.util.function.Consumer<String> consumer) {
                    return complete(request);
                }
            };
        }
    }
}
