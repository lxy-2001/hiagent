package com.agentflow.demo.approval;

import com.agentflow.core.tool.*;
import com.agentflow.mcp.*;
import com.agentflow.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalAssemblyTest {
    @Test void disabledMcpDoesNotConnectAndCustomRegistryAndPolicyAreRetained() {
        var client = mock(McpClientOperations.class);
        var registry = new InMemoryToolRegistry(java.util.List.of());
        var policy = ToolExecutionPolicy.denyAll();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(McpAutoConfiguration.class,
                ToolPolicyAutoConfiguration.class, AgentToolAutoConfiguration.class))
                .withBean(McpClientOperations.class, () -> client).withBean(ToolRegistry.class, () -> registry)
                .withBean(ToolExecutionPolicy.class, () -> policy).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ToolRegistry.class);
                    assertThat(context.getBean(ToolRegistry.class)).isSameAs(registry);
                    assertThat(context.getBean(ToolExecutionPolicy.class)).isSameAs(policy);
                    assertThat(context).doesNotHaveBean(McpToolProvider.class);
                });
        verify(client, never()).initialize(any());
        verify(client, never()).listTools(any(), any());
        verify(client, never()).call(anyString(), any(), any());
    }
    @Test void explicitlyEnabledMcpWithoutServersFailsInsteadOfInstallingPlaceholder() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(McpAutoConfiguration.class,ToolPolicyAutoConfiguration.class))
                .withPropertyValues("agentflow.mcp.enabled=true").run(context -> assertThat(context).hasFailed());
    }
}
