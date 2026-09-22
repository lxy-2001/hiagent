package com.agentflow.demo;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.web.auth.JwtService;
import com.agentflow.web.run.RunCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentFlowDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:conversation-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "agentflow.knowledge.bootstrap.enabled=false", "agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32"})
class ConversationEndToEndTest {
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired RunCoordinator coordinator;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AgentModelClient model;
    @MockitoBean StringRedisTemplate redis;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.agentflow.web.conversation.PersistentContextSource source;
    private final List<AgentModelRequest> requests = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private String token;

    @BeforeEach void setup() {
        requests.clear();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.hasKey(anyString())).thenReturn(false);
        token = jwt.issueAccessToken("conversation-owner", "owner", List.of("USER")).value();
        doAnswer(invocation -> {
            AgentModelRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.input().equals("fail intentionally")) throw new IllegalStateException("offline model failure");
            return new FinalAnswerDecision("decision-" + request.taskId(), "answer " + requests.size(), new TokenUsage(10, 5, 15));
        }).when(model).decide(any());
    }

    @Test void sourceFailureProducesFailedRunWithoutCallingModel() throws Exception {
        doThrow(new com.agentflow.core.context.ContextSource.ContextSourceException("offline injected failure"))
                .when(source).load(any(), any(), any());
        var accepted = create(Map.of("input", "source failure"));
        String task = accepted.get("taskId").asText();
        awaitReleased(task, "FAILED");
        var result = json.readTree(send("/api/agent/tasks/" + task, "GET", null).body());
        assertThat(result.get("terminationReason").asText()).isEqualTo("CONTEXT_SOURCE_UNAVAILABLE");
        verifyNoInteractions(model);
    }

    @Test void cancellationKeepsSessionBusyUntilRealWorkerExitThenAllowsContinuation() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            return new FinalAnswerDecision("cancelled-decision", "late answer", TokenUsage.empty());
        }).when(model).decide(any());
        var accepted = create(Map.of("input", "cancel blocking model"));
        String task = accepted.get("taskId").asText();
        String session = accepted.get("sessionId").asText();
        try {
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(send("/api/agent/tasks/" + task + "/cancel", "POST", null).statusCode()).isIn(200, 202);
            var busy = send("/api/agent/tasks", "POST", Map.of("input", "too soon", "sessionId", session));
            assertThat(busy.statusCode()).isEqualTo(409);
            assertThat(json.readTree(busy.body()).get("code").asText()).isEqualTo("SESSION_BUSY");
        } finally { release.countDown(); }
        awaitReleased(task, "CANCELLED");
        var continuation = create(Map.of("input", "after cancellation", "sessionId", session));
        awaitReleased(continuation.get("taskId").asText(), "SUCCEEDED");
    }

    @Test void realHttpRunsUseDurableHistoryAndOnlyExplicitCurrentMemoryAfterFlywayUpgrade() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=true and version in ('1','2','3','4')", Integer.class)).isEqualTo(4);
        JsonNode first = create(Map.of("input", "first question"));
        String session = first.get("sessionId").asText();
        awaitReleased(first.get("taskId").asText(), "SUCCEEDED");
        assertThat(requests.get(0).messages()).extracting(ModelMessage::role).containsExactly("system", "user");
        String memoryPath = "/api/agent/sessions/" + session + "/memories/project_stack";
        assertThat(send(memoryPath, "PUT", Map.of("value", "Java 17", "expectedVersion", "0")).statusCode()).isEqualTo(200);
        JsonNode failed = create(Map.of("input", "fail intentionally", "sessionId", session));
        awaitReleased(failed.get("taskId").asText(), "FAILED");
        assertThat(requests.get(1).messages()).extracting(ModelMessage::content).anyMatch(value -> value.contains("Java 17"));
        assertThat(send(memoryPath + "?expectedVersion=1", "DELETE", null).statusCode()).isEqualTo(200);
        JsonNode third = create(Map.of("input", "third question", "sessionId", session));
        String thirdId = third.get("taskId").asText();
        awaitReleased(thirdId, "SUCCEEDED");
        assertThat(requests.get(2).messages()).extracting(ModelMessage::content)
                .contains("first question", "answer 1", "third question").doesNotContain("fail intentionally")
                .noneMatch(value -> value.contains("Java 17"));
        var turns = json.readTree(send("/api/agent/sessions/" + session + "/turns", "GET", null).body());
        assertThat(turns.get("items").size()).isEqualTo(3);
        assertThat(turns.get("items").get(1).get("finalAnswer").isNull()).isTrue();
        assertThat(turns.get("untilSequence").asText()).isEqualTo("3");
        var replay = send("/api/agent/tasks/" + thirdId + "/events", "GET", null);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("CONTEXT_ASSEMBLY", "RUN_TERMINATED");
        var reconnect = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/agent/tasks/" + thirdId + "/events"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).header("Last-Event-ID", "1").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reconnect.statusCode()).isEqualTo(200);
        assertThat(reconnect.body()).contains("id:2\n", "RUN_TERMINATED").doesNotContain("id:1\n");
    }

    private JsonNode create(Map<String, String> body) throws Exception {
        var response = send("/api/agent/tasks", "POST", body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(202);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Location")).isPresent();
        return json.readTree(response.body());
    }
    private void awaitReleased(String task, String expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            var response = send("/api/agent/tasks/" + task, "GET", null);
            if (response.statusCode() == 200) {
                var snapshot = json.readTree(response.body());
                if (!snapshot.get("finishedAt").isNull() && coordinator.inFlightCount() == 0) {
                    assertThat(snapshot.get("status").asText()).withFailMessage(response.body()).isEqualTo(expected);
                    return;
                }
            }
            Thread.sleep(20);
        }
        fail("run did not finish and release");
    }
    private String base() { return "http://localhost:" + port; }
    private HttpResponse<String> send(String path, String method, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
