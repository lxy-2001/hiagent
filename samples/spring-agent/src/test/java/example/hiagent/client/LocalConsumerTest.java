package example.hiagent.client;
import com.agentflow.core.*;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class LocalConsumerTest {
    @Test void selectedLocalToolRunsAndUnselectedLibrariesAreAbsent() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        AgentTool tool = new AgentTool() {
            public ToolDefinition definition() { return new ToolDefinition("local.read", "Read fixture", RiskLevel.LOW, new ToolSchema(Map.of())); }
            public ToolResult execute(ToolArguments args, ToolContext context) { calls.incrementAndGet(); return ToolResult.success("local.read", "value"); }
        };
        new ApplicationContextRunner().withUserConfiguration(ClientApplication.class)
                .withBean(AgentTool.class, () -> tool)
                .withBean(ToolExecutionPolicy.class, () -> ToolExecutionPolicy.rules(Map.of("local.read", new ToolPolicyDecision(
                        ToolPolicyDecision.Action.ALLOW, RiskLevel.LOW, ToolPolicyDecision.Effect.READ_ONLY, "Read", Set.of()))))
                .withBean(AgentModelClient.class, () -> request -> request.iteration() == 1
                        ? new ToolCallDecision("d", new ToolCall("c", "local.read", new ToolArguments(Map.of())), TokenUsage.empty())
                        : new FinalAnswerDecision("f", "local-ok", TokenUsage.empty()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = context.getBean(AgentRuntime.class).run(new AgentRequest("r", "s", "u", "hello"), e -> {});
                    assertThat(result.finalAnswer()).isEqualTo("local-ok");
                    assertThat(calls).hasValue(1);
                    for (String type : List.of("com.agentflow.llm.AgentLlmAutoConfiguration", "com.agentflow.rag.AgentRagAutoConfiguration",
                            "com.agentflow.mcp.McpAutoConfiguration", "com.agentflow.web.autoconfigure.AgentWebAutoConfiguration", "jakarta.persistence.EntityManager"))
                        assertThatThrownBy(() -> Class.forName(type)).isInstanceOf(ClassNotFoundException.class);
                    assertThat(AgentRuntime.class.getProtectionDomain().getCodeSource().getLocation().toString()).endsWith(".jar");
                });
    }
}
