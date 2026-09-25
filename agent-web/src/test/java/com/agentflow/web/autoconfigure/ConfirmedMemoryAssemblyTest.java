package com.agentflow.web.autoconfigure;

import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.tool.InMemoryToolRegistry;
import com.agentflow.web.AgentWebRuntimeAssemblyTest;
import com.agentflow.web.memory.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmedMemoryAssemblyTest {
    @Test void registersMemoryServiceAndControllerWithExplicitRepository() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(com.agentflow.tool.ToolPolicyAutoConfiguration.class, AgentWebAutoConfiguration.class))
                .withPropertyValues("agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32")
                .withUserConfiguration(AgentWebRuntimeAssemblyTest.Dependencies.class)
                .withBean(AgentModelClient.class, () -> request -> new FinalAnswerDecision("d", "answer", TokenUsage.empty()))
                .withBean(ToolRegistry.class, () -> new InMemoryToolRegistry(List.of()))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ConfirmedMemoryService.class).hasSingleBean(ConfirmedMemoryController.class));
    }
}
