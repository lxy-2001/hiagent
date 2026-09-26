package com.agentflow.web.autoconfigure;

import com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration;
import com.agentflow.core.*;
import com.agentflow.core.context.ContextAssembler;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.ToolExecutor;
import com.agentflow.web.agent.JpaStepRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeWiringMigrationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(com.agentflow.tool.ToolPolicyAutoConfiguration.class,
                        AgentWebAutoConfiguration.class, AgentRuntimeAutoConfiguration.class))
                .withUserConfiguration(AgentWebAutoConfigurationTest.TestDependencies.class)
                .withPropertyValues("agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32");
    }

    @Test void webUsesStarterAndKeepsItsPersistenceRecorder() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentRuntime.class)
                    .hasSingleBean(ContextAssembler.class).hasSingleBean(ToolExecutor.class);
            assertThat(context.getBean(StepRecorder.class)).isInstanceOf(JpaStepRecorder.class);
            assertThat(context.getBeanFactory().getBeanDefinition("agentRuntime").getFactoryBeanName())
                    .isEqualTo(AgentRuntimeAutoConfiguration.class.getName());
        });
    }

    @Test void suppliedRecorderAndClockAreActuallyUsed() {
        var steps = new java.util.ArrayList<AgentStepRecord>();
        var ticks = new java.util.concurrent.atomic.AtomicInteger();
        StepRecorder recorder = steps::add;
        runner().withBean(StepRecorder.class, () -> recorder)
                .withBean(TimeSource.class, () -> () -> { ticks.incrementAndGet(); return System.nanoTime(); })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = context.getBean(AgentRuntime.class).run(new AgentRequest("r", "s", "u", "hello"), e -> {});
                    assertThat(result.finalAnswer()).isEqualTo("model");
                    assertThat(steps).isNotEmpty();
                    assertThat(ticks.get()).isPositive();
                    assertThat(context.getBean(StepRecorder.class)).isSameAs(recorder);
                });
    }
}
