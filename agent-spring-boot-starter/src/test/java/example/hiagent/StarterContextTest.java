package example.hiagent;

import com.agentflow.core.*;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.step.StepRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class StarterContextTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Consumer {}

    static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Consumer.class);
    }

    @Test void runsWithoutWebOrPersistence() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        runner().withBean(AgentModelClient.class, () -> request -> {
            calls.incrementAndGet();
            return new FinalAnswerDecision("decision", "hello", TokenUsage.empty());
        }).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentRuntime.class).doesNotHaveBean(StepRecorder.class);
            var result = context.getBean(AgentRuntime.class).run(new AgentRequest("run", "session", "user", "hello"), event -> {});
            assertThat(result.finalAnswer()).isEqualTo("hello");
            assertThat(calls).hasValue(1);
            assertThat(context.getBeanFactory().getBeanDefinitionNames()).noneMatch(name -> name.contains("dataSource") || name.contains("redis"));
        });
    }

    @Test void missingModelFailsClearly() {
        runner().run(context -> assertThat(context).hasFailed());
    }

    @Test void suppliedRuntimeDoesNotRequireModel() {
        AgentRuntime custom = (request, sink, options) -> new AgentResult(request.taskId(), "custom", java.util.List.of());
        runner().withBean(AgentRuntime.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentRuntime.class);
            assertThat(context.getBean(AgentRuntime.class)).isSameAs(custom);
            assertThat(context.getBean(AgentRuntime.class).run(new AgentRequest("r", "s", "u", "hi"), e -> {}).finalAnswer()).isEqualTo("custom");
        });
    }
}

