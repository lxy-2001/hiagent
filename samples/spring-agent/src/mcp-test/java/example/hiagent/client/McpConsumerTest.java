package example.hiagent.client;
import com.agentflow.core.*;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.*;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class McpConsumerTest {
    @Test void sdkDiscoveryReadAndMissingApprovalStayWithinPolicy() throws Exception {
        var calls = new AtomicInteger();
        var writes = new AtomicInteger();
        var connections = new AtomicInteger();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                connections.incrementAndGet();
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); return; }
                var request = json.readTree(exchange.getRequestBody().readAllBytes());
                if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
                Object result;
                switch (request.path("method").asString()) {
                    case "initialize" -> result = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "sample", "version", "1"));
                    case "tools/list" -> result = Map.of("tools", List.of("read", "write", "hidden").stream().map(name -> Map.of("name", name,
                            "description", "Fixture tool", "inputSchema", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))).toList());
                    case "tools/call" -> {
                        calls.incrementAndGet();
                        if (request.path("params").path("name").asString().equals("write")) writes.incrementAndGet();
                        result = Map.of("content", List.of(Map.of("type", "text", "text", "mcp-value")), "isError", false);
                    }
                    default -> throw new IllegalStateException("unsupported fixture method");
                }
                byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var runner = new ApplicationContextRunner().withUserConfiguration(ClientApplication.class)
                    .withPropertyValues("agentflow.mcp.servers[0].id=sample", "agentflow.mcp.servers[0].url=http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                            "agentflow.mcp.servers[0].allowed-tools=read,write")
                    .withBean(AgentModelClient.class, () -> request -> request.iteration() == 1
                            ? new ToolCallDecision("d", new ToolCall("c", "mcp.sample." + request.input(), new ToolArguments(Map.of())), TokenUsage.empty())
                            : new FinalAnswerDecision("f", "mcp-ok", TokenUsage.empty()));
            runner.run(context -> { assertThat(context).hasNotFailed(); assertThat(connections).hasValue(0); });
            runner.withPropertyValues("agentflow.mcp.enabled=true").run(context -> {
                assertThat(context).hasNotFailed();
                context.getBean(AgentRuntime.class).run(new AgentRequest("deny", "s", "u", "read"), e -> {});
                assertThat(calls).hasValue(0);
            });
            runner.withPropertyValues("agentflow.mcp.enabled=true")
                    .withBean(ToolExecutionPolicy.class, () -> ToolExecutionPolicy.rules(Map.of(
                            "mcp.sample.read", new ToolPolicyDecision(ToolPolicyDecision.Action.ALLOW, RiskLevel.LOW, ToolPolicyDecision.Effect.READ_ONLY, "Read", Set.of()),
                            "mcp.sample.write", new ToolPolicyDecision(ToolPolicyDecision.Action.REQUIRE_APPROVAL, RiskLevel.HIGH, ToolPolicyDecision.Effect.WRITE, "Write", Set.of()))))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(ToolRegistry.class).enabledToolNames()).containsExactlyInAnyOrder("mcp.sample.read", "mcp.sample.write");
                        var runtime = context.getBean(AgentRuntime.class);
                        assertThat(runtime.run(new AgentRequest("read", "s", "u", "read"), e -> {}).finalAnswer()).isEqualTo("mcp-ok");
                        assertThat(calls).hasValue(1);
                        var denied = runtime.run(new AgentRequest("write", "s", "u", "write"), e -> {});
                        assertThat(denied.status().name()).isEqualTo("FAILED");
                        assertThat(writes).hasValue(0);
                        assertThat(calls).hasValue(1);
                        assertThatThrownBy(() -> Class.forName("com.agentflow.llm.AgentLlmAutoConfiguration")).isInstanceOf(ClassNotFoundException.class);
                    });
        } finally { server.stop(0); }
    }
}
