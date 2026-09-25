package com.agentflow.mcp;
import com.agentflow.core.tool.*;
import com.agentflow.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import java.util.*;
class McpAutoConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(McpAutoConfiguration.class,
                ToolPolicyAutoConfiguration.class,AgentToolAutoConfiguration.class));
    }
    @Test void disabledNeverCreatesProviderOrConnects() {
        runner().run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(com.agentflow.mcp.McpToolProvider.class);});
    }
    @Test void enabledWithoutServersFails() {
        runner().withPropertyValues("agentflow.mcp.enabled=true").run(context->assertThat(context).hasFailed());
    }
    @Test void customRegistryBypassesDefaultMcpDiscoveryEvenWhenEnabled() {
        ToolRegistry registry=mock(ToolRegistry.class);
        runner().withPropertyValues("agentflow.mcp.enabled=true").withBean(ToolRegistry.class,()->registry)
                .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(com.agentflow.mcp.McpToolProvider.class);});
    }
    @Test void customClientIsUsedAndToolsJoinDefaultRegistry() {
        var client=new McpToolProviderTest.Client(i->new McpClientOperations.Page(List.of(McpToolProviderTest.tool(McpToolProviderTest.valid())),null));
        runner().withPropertyValues("agentflow.mcp.enabled=true","agentflow.mcp.servers[0].id=demo",
                "agentflow.mcp.servers[0].url=https://example.invalid/mcp","agentflow.mcp.servers[0].allowed-tools[0]=project_info")
                .withBean(McpClientOperations.class,()->client)
                .run(context->{assertThat(context).hasNotFailed();assertThat(context.getBean(ToolRegistry.class).enabledToolNames()).containsExactly("mcp.demo.project_info");
                    assertThat(client.lists.get()).isEqualTo(1);});
    }
}
