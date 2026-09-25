package com.agentflow.web.autoconfigure;

import com.agentflow.core.context.ContextSource;
import com.agentflow.core.context.ContextSeed;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.tool.InMemoryToolRegistry;
import com.agentflow.web.AgentWebRuntimeAssemblyTest;
import com.agentflow.web.conversation.PersistentContextSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ConversationSourceAssemblyTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(com.agentflow.tool.ToolPolicyAutoConfiguration.class, AgentWebAutoConfiguration.class))
                .withPropertyValues("agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32")
                .withUserConfiguration(AgentWebRuntimeAssemblyTest.Dependencies.class)
                .withBean(AgentModelClient.class, () -> request -> new FinalAnswerDecision("d", "answer", TokenUsage.empty()))
                .withBean(ToolRegistry.class, () -> new InMemoryToolRegistry(List.of()));
    }
    @Test void preparationTimeoutIsBounded() {
        assertThatThrownBy(() -> new com.agentflow.web.conversation.ConversationProperties(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.agentflow.web.conversation.ConversationProperties(java.time.Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void defaultIsPersistentAndApplicationSourceOverridesIt() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContextSource.class);
            assertThat(context.getBean(ContextSource.class)).isInstanceOf(PersistentContextSource.class);
        });
        ContextSource source = (query, timeout, cancellation) -> ContextSeed.empty();
        runner().withBean(ContextSource.class, () -> source).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContextSource.class);
            assertThat(context.getBean(ContextSource.class)).isSameAs(source);
            assertThat(context).doesNotHaveBean(PersistentContextSource.class);
        });
    }
}
