package com.agentflow.web.autoconfigure;

import com.agentflow.core.AgentRuntime;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentEventSink;
import com.agentflow.core.context.*;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.tool.InMemoryToolRegistry;
import com.agentflow.web.AgentWebRuntimeAssemblyTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContextPolicyAssemblyTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentWebAutoConfiguration.class))
                .withPropertyValues("agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32")
                .withUserConfiguration(AgentWebRuntimeAssemblyTest.Dependencies.class)
                .withBean(AgentModelClient.class, () -> request -> new FinalAnswerDecision("d", "answer", TokenUsage.empty()))
                .withBean(ToolRegistry.class, () -> new InMemoryToolRegistry(List.of()));
    }

    @Test
    void defaultsArePreciseAndUsable() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContextPolicy.class)
                    .hasSingleBean(TokenEstimator.class).hasSingleBean(ContextTextPolicy.class).hasSingleBean(ContextAssembler.class);
            assertThat(context.getBean(ContextPolicy.class)).isEqualTo(ContextPolicy.defaults());
        });
    }

    @Test
    void customPolicyAndEstimatorReachDefaultRuntime() {
        var policy = new ContextPolicy("custom system", "test", 1);
        var estimator = mock(TokenEstimator.class);
        when(estimator.version()).thenReturn("custom-v1");
        runner().withBean(ContextPolicy.class, () -> policy).withBean(TokenEstimator.class, () -> estimator).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ContextPolicy.class)).isSameAs(policy);
            assertThat(context.getBean(TokenEstimator.class)).isSameAs(estimator);
            var result = context.getBean(AgentRuntime.class).run(new AgentRequest("r", "s", "u", "hello"), AgentEventSink.NOOP);
            assertThat(result.terminationReason().name()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
            verify(estimator).estimateInput(anyList(), anyList());
        });
    }

    @Test
    void applicationAssemblerAndRuntimeOverrideDefaults() {
        var assembler = new ContextAssembler(ContextPolicy.defaults(), new Utf8TokenEstimator(), new ContextTextPolicy());
        var runtime = mock(AgentRuntime.class);
        runner().withBean(ContextAssembler.class, () -> assembler).withBean(AgentRuntime.class, () -> runtime).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContextAssembler.class).hasSingleBean(AgentRuntime.class);
            assertThat(context.getBean(ContextAssembler.class)).isSameAs(assembler);
            assertThat(context.getBean(AgentRuntime.class)).isSameAs(runtime);
        });
    }
}
