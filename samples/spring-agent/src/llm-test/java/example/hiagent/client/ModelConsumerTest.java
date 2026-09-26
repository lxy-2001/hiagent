package example.hiagent.client;
import com.agentflow.core.*;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.llm.OpenAiAgentModelClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class ModelConsumerTest {
    @Test void realProviderAdapterUsesOnlySelectedLoopbackEndpoint() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("fixture-model");
            byte[] body = "{\"id\":\"fixture-d\",\"model\":\"fixture-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"adapter-ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(ClientApplication.class)
                    .withPropertyValues("agentflow.model.provider=openai", "agentflow.model.api-key=fixture-only",
                            "agentflow.model.chat-model=fixture-model", "agentflow.model.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AgentModelClient.class)).isInstanceOf(OpenAiAgentModelClient.class);
                        assertThat(calls).hasValue(0);
                        var result = context.getBean(AgentRuntime.class).run(new AgentRequest("r", "s", "u", "hello"), e -> {});
                        assertThat(result.finalAnswer()).isEqualTo("adapter-ok");
                        assertThat(calls).hasValue(1);
                        assertThatThrownBy(() -> Class.forName("com.agentflow.web.autoconfigure.AgentWebAutoConfiguration")).isInstanceOf(ClassNotFoundException.class);
                    });
        } finally { server.stop(0); }
    }
}
