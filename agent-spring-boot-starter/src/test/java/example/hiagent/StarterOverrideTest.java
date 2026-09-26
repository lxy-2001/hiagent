package example.hiagent;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.tool.*;
import com.agentflow.tool.InMemoryToolRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StarterOverrideTest {
    private AgentResult run(org.springframework.context.ApplicationContext context) {
        return context.getBean(AgentRuntime.class).run(new AgentRequest("r", "s", "u", "hello"), e -> {});
    }
    private AgentModelClient model() {
        return request -> new FinalAnswerDecision("done", "ok", TokenUsage.empty());
    }

    @Test void contextPolicyEstimatorTextAndClockAreUsed() {
        var policy = new ContextPolicy("custom system", "custom-v1", 8192);
        var estimator = spy(new Utf8TokenEstimator());
        var text = spy(new ContextTextPolicy());
        var ticks = new AtomicInteger();
        TimeSource clock = () -> { ticks.incrementAndGet(); return System.nanoTime(); };
        StarterContextTest.runner().withBean(AgentModelClient.class, () -> request -> {
            assertThat(request.messages().get(0).content()).contains("custom system");
            return model().decide(request);
        }).withBean(ContextPolicy.class, () -> policy).withBean(TokenEstimator.class, () -> estimator)
                .withBean(ContextTextPolicy.class, () -> text).withBean(TimeSource.class, () -> clock).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ContextPolicy.class).hasSingleBean(TokenEstimator.class);
                    assertThat(context.getBean(ContextPolicy.class)).isSameAs(policy);
                    assertThat(context.getBean(ContextTextPolicy.class)).isSameAs(text);
                    assertThat(context.getBean(TimeSource.class)).isSameAs(clock);
                    assertThat(run(context).finalAnswer()).isEqualTo("ok");
                    verify(estimator, atLeastOnce()).estimateInput(anyList(), anyList());
                    verify(text, atLeastOnce()).sanitizeInput("hello");
                    assertThat(ticks.get()).isPositive();
                });
    }

    @Test void customAssemblerIsInvokedExactlyOnce() {
        var assembler = spy(new ContextAssembler(ContextPolicy.defaults(), new Utf8TokenEstimator(), new ContextTextPolicy()));
        StarterContextTest.runner().withBean(AgentModelClient.class, this::model)
                .withBean(ContextAssembler.class, () -> assembler).run(context -> {
                    assertThat(context).hasSingleBean(ContextAssembler.class);
                    assertThat(context.getBean(ContextAssembler.class)).isSameAs(assembler);
                    assertThat(run(context).finalAnswer()).isEqualTo("ok");
                    verify(assembler).assemble(any(), anyList(), anyList(), eq(1), anyInt());
                });
    }

    @Test void registryExecutorNormalizerAndPolicyAreUsed() {
        AgentTool tool = new AgentTool() {
            public ToolDefinition definition() { return new ToolDefinition("local.read", "Read fixture", RiskLevel.LOW, new ToolSchema(Map.of())); }
            public ToolResult execute(ToolArguments args, ToolContext context) { return ToolResult.success("local.read", "value"); }
        };
        var registry = spy(new InMemoryToolRegistry(List.of(tool)));
        var normalizer = spy(new DefaultToolResultNormalizer());
        var executor = spy(new DefaultToolExecutor(registry, normalizer));
        var policyCalls = new AtomicInteger();
        var rules = ToolExecutionPolicy.rules(Map.of("local.read", new ToolPolicyDecision(
                ToolPolicyDecision.Action.ALLOW, RiskLevel.LOW, ToolPolicyDecision.Effect.READ_ONLY, "Read", Set.of())));
        ToolExecutionPolicy policy = name -> { policyCalls.incrementAndGet(); return rules.decide(name); };
        StarterContextTest.runner().withBean(AgentModelClient.class, () -> request -> request.iteration() == 1
                ? new ToolCallDecision("call-decision", new ToolCall("call", "local.read", new ToolArguments(Map.of())), TokenUsage.empty())
                : model().decide(request)).withBean(ToolRegistry.class, () -> registry)
                .withBean(ToolExecutor.class, () -> executor).withBean(ToolResultNormalizer.class, () -> normalizer)
                .withBean(ToolExecutionPolicy.class, () -> policy).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ToolRegistry.class).hasSingleBean(ToolExecutor.class)
                            .hasSingleBean(ToolResultNormalizer.class).hasSingleBean(ToolExecutionPolicy.class);
                    assertThat(context.getBean(ToolRegistry.class)).isSameAs(registry);
                    assertThat(context.getBean(ToolExecutor.class)).isSameAs(executor);
                    assertThat(context.getBean(ToolResultNormalizer.class)).isSameAs(normalizer);
                    assertThat(context.getBean(ToolExecutionPolicy.class)).isSameAs(policy);
                    assertThat(run(context).finalAnswer()).isEqualTo("ok");
                    verify(executor).execute(any(), any());
                    verify(normalizer, atLeastOnce()).normalize(any(), any());
                    verify(registry, atLeastOnce()).enabledDefinitions();
                    assertThat(policyCalls.get()).isPositive();
                });
    }

    @Test void ambiguousModelsFailInsteadOfSelectingOne() {
        StarterContextTest.runner().withBean("first", AgentModelClient.class, this::model)
                .withBean("second", AgentModelClient.class, this::model)
                .run(context -> assertThat(context).hasFailed());
    }
}
