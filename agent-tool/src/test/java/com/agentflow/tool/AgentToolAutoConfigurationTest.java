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
        runner().run(context -> assertThat(context).doesNotHaveBean(McpToolProvider.class));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentToolAutoConfiguration.class));
    }
}
