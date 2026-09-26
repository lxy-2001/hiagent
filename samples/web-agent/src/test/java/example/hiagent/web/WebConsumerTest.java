package example.hiagent.web;

import com.agentflow.core.*;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebConsumerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private String base, token;

    @Test void publicLoginApprovalAndRealStepPersistenceWorkOutsideRepository() throws Exception {
        var app = new SpringApplication(WebConsumerApplication.class, Fixture.class);
        app.setAdditionalProfiles("consumer-fixture");
        try (var context = app.run("--server.address=127.0.0.1", "--server.port=0", "--logging.level.root=ERROR",
                "--spring.datasource.url=jdbc:h2:mem:consumer-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.jpa.hibernate.ddl-auto=none", "--spring.data.redis.repositories.enabled=false",
                "--agentflow.rag.enabled=false", "--agentflow.security.jwt.secret=consumer-fixture-secret-not-production-008",
                "--server.shutdown=immediate")) {
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            // Test-owned account seeded with public SQL schema and Spring's encoder; no Web internals.
            context.getBean(JdbcTemplate.class).update("insert into sys_user(id,username,password_hash,display_name,enabled,created_at,updated_at) values(?,?,?,?,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    "sample-owner", "sample-user", context.getBean(PasswordEncoder.class).encode("fixture-password"), "Fixture");
            token = request("POST", "/api/auth/login", Map.of("username", "sample-user", "password", "fixture-password")).path("accessToken").asString();
            var created = request("POST", "/api/agent/tasks", Map.of("input", "write fixture note"));
            String id = created.path("taskId").asString();
            await(id, "WAITING_APPROVAL");
            assertThat(context.getBean(AtomicInteger.class)).hasValue(0);
            var approval = request("GET", "/api/agent/tasks/" + id + "/approvals", null).path("items").get(0);
            request("POST", "/api/agent/tasks/" + id + "/approvals/" + approval.path("approvalId").asString() + "/decision", Map.of("decision", "APPROVE"));
            var done = await(id, "SUCCEEDED");
            assertThat(done.path("recordingComplete").asBoolean()).isTrue();
            assertThat(done.path("finalAnswer").asString()).isEqualTo("web-ok");
            assertThat(context.getBean(AtomicInteger.class)).hasValue(1);
            var steps = request("GET", "/api/agent/tasks/" + id + "/steps", null);
            assertThat(steps.toString()).contains("TERMINATION", "TOOL_RESULT");
            assertThat(AgentRuntime.class.getProtectionDomain().getCodeSource().getLocation().toString()).endsWith(".jar");
        }
    }
    private JsonNode await(String id, String wanted) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/agent/tasks/" + id))
                    .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 503) { Thread.sleep(10); continue; }
            assertThat(response.statusCode()).isEqualTo(200);
            var value = json.readTree(response.body());
            if (value.path("status").asString().equals(wanted)) return value;
            if (Set.of("FAILED", "CANCELLED", "TIMED_OUT").contains(value.path("status").asString())) throw new AssertionError(value.path("terminationReason").asString());
            Thread.sleep(10);
        }
        throw new AssertionError("run deadline");
    }
    private JsonNode request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return json.readTree(response.body());
    }
    @TestConfiguration(proxyBeanMethods = false) @Profile("consumer-fixture")
    static class Fixture {
        @Bean AtomicInteger writes() { return new AtomicInteger(); }
        @Bean AgentTool tool(AtomicInteger writes) {
            return new AgentTool() {
                public ToolDefinition definition() { return new ToolDefinition("sample.note", "Write test note", RiskLevel.HIGH, new ToolSchema(Map.of())); }
                public ToolResult execute(ToolArguments args, ToolContext context) { writes.incrementAndGet(); return ToolResult.success("sample.note", "receipt"); }
            };
        }
        @Bean ToolExecutionPolicy policy() { return ToolExecutionPolicy.rules(Map.of("sample.note", new ToolPolicyDecision(
                ToolPolicyDecision.Action.REQUIRE_APPROVAL, RiskLevel.HIGH, ToolPolicyDecision.Effect.WRITE, "Write test note", Set.of()))); }
        @Bean AgentModelClient model() {
            return request -> request.iteration() == 1
                    ? new ToolCallDecision("d", new ToolCall("c", "sample.note", new ToolArguments(Map.of())), TokenUsage.empty())
                    : new FinalAnswerDecision("f", "web-ok", TokenUsage.empty());
        }
        @Bean StringRedisTemplate redis() {
            var redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values); when(values.increment(anyString())).thenReturn(1L); when(redis.hasKey(anyString())).thenReturn(false);
            return redis;
        }
    }
}
