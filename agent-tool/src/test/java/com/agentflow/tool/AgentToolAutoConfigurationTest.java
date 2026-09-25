package com.agentflow.tool;

import com.agentflow.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentToolAutoConfigurationTest {

    @Test
    void createsARealEmptyRegistryWhenNoToolsAreDeclared() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(ToolRegistry.class);
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertThat(registry).isInstanceOf(InMemoryToolRegistry.class);
            assertThat(registry.enabledToolNames()).isEmpty();
        });
    }

    @Test
    void applicationRegistryOverridesTheDefaultRegistry() {
        ToolRegistry custom = mock(ToolRegistry.class);

        runner().withBean(ToolRegistry.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(ToolRegistry.class);
            assertThat(context).getBean(ToolRegistry.class).isSameAs(custom);
        });
    }

    @Test
    void mcpProviderIsNotRegisteredAutomatically() {
        runner().run(context -> assertThat(context).doesNotHaveBean(com.agentflow.core.tool.ToolProvider.class));
    }

    @Test
    void providersContributeToTheSameRegistryAndDuplicatesFail() {
        com.agentflow.core.tool.ToolProvider provider=()->java.util.List.of(new UppercaseTextTool());
        runner().withBean(com.agentflow.core.tool.ToolProvider.class,()->provider).run(context->
                assertThat(context.getBean(ToolRegistry.class).enabledToolNames()).containsExactly("uppercase-text"));
        runner().withBean(com.agentflow.core.tool.ToolProvider.class,()->provider)
                .withBean(com.agentflow.core.tool.AgentTool.class,UppercaseTextTool::new)
                .run(context->assertThat(context).hasFailed());
    }


    @Test
    void combinedToolCatalogCannotExceed128Entries() {
        var tools=java.util.stream.IntStream.range(0,129).mapToObj(index -> new com.agentflow.core.tool.AgentTool() {
            public com.agentflow.core.tool.ToolDefinition definition() {
                return new com.agentflow.core.tool.ToolDefinition("fixture-"+index,"fixture",com.agentflow.core.tool.RiskLevel.LOW,
                        new com.agentflow.core.tool.ToolSchema(java.util.Map.of()));
            }
            public com.agentflow.core.tool.ToolResult execute(com.agentflow.core.tool.ToolArguments arguments,com.agentflow.core.tool.ToolContext context) {
                throw new AssertionError("discovery must not execute tools");
            }
        }).map(tool -> (com.agentflow.core.tool.AgentTool)tool).toList();
        runner().withBean(com.agentflow.core.tool.ToolProvider.class,()->()->tools)
                .run(context->assertThat(context).hasFailed());
        runner().withBean(com.agentflow.core.tool.ToolProvider.class,()->()->tools.subList(0,128))
                .run(context->assertThat(context.getBean(ToolRegistry.class).enabledToolNames()).hasSize(128));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentToolAutoConfiguration.class));
    }
}
