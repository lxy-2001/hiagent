package com.agentflow.demo;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import com.agentflow.web.agent.AgentSessionRepository;
import com.agentflow.web.agent.AgentStepRepository;
import com.agentflow.web.agent.AgentTaskRepository;
import com.agentflow.web.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AgentFlowDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:lifecycle-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "agentflow.knowledge.bootstrap.enabled=false",
        "agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32"})
@SuppressWarnings("unchecked")
class RunLifecycleEndToEndTest {
    @LocalServerPort int port;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper mapper;
    @Autowired AgentStepRepository steps;
    @Autowired AgentTaskRepository tasks;
    @Autowired AgentSessionRepository sessions;
    @MockitoBean AgentRuntime runtime;
    @MockitoBean StringRedisTemplate redis;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private String token;

    @BeforeEach
    void setUp() {
        steps.deleteAll();
        tasks.deleteAll();
        sessions.deleteAll();
        reset(runtime);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.hasKey(anyString())).thenReturn(false);
        token = jwtService.issueAccessToken("owner", "owner", List.of("USER")).value();
    }

    @Test
    void twoToolRunCanBeObservedLateReconnectedAndQueriedFromCommittedState() throws Exception {
        when(runtime.run(any(), any(), any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            AgentEventSink sink = invocation.getArgument(1);
            sink.publish(new AgentEvent(request.taskId(), AgentStepType.TOOL_RESULT,
                    "lookup", "first tool result", Instant.now(), 1, "call-1", false));
            sink.publish(new AgentEvent(request.taskId(), AgentStepType.TOOL_RESULT,
                    "calculate", "second tool result", Instant.now(), 2, "call-2", false));
            return AgentResult.success(request.taskId(), "combined answer", List.of(
                    AgentStepRecord.success(request.taskId(), 1, AgentStepType.TOOL_RESULT,
                            "lookup", "one", "first tool result", 2),
                    AgentStepRecord.success(request.taskId(), 2, AgentStepType.TOOL_RESULT,
                            "calculate", "two", "second tool result", 3),
                    AgentStepRecord.success(request.taskId(), 3, AgentStepType.TERMINATION,
                            "terminal", null, "combined answer", 1, null, null,
                            null, null, true)), new TokenUsage(4, 5, 9));
        });

        String taskId = create("run two tools");
        awaitTerminal(taskId);

        HttpResponse<InputStream> first = events(taskId, 0);
        BufferedReader firstStream = reader(first);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(readEventId(firstStream)).isEqualTo("1");
        assertThat(readEventId(firstStream)).isEqualTo("2");
        first.body().close();

        HttpResponse<InputStream> reconnect = events(taskId, 2);
        BufferedReader replay = reader(reconnect);
        List<String> remainingIds = readAllEventIds(replay);
        assertThat(remainingIds).containsExactly("3", "4", "5");

        Map<String, Object> snapshot = getJson("/api/agent/tasks/" + taskId);
        assertThat(snapshot).containsEntry("status", "SUCCEEDED")
                .containsEntry("finalAnswer", "combined answer")
                .containsEntry("terminationReason", "COMPLETED");
        List<?> persistedSteps = getList("/api/agent/tasks/" + taskId + "/steps");
        assertThat(persistedSteps).hasSize(3);
        verify(runtime, times(1)).run(any(), any(), any());
    }

    @Test
    void authenticatedCancellationPropagatesToTheRunningRuntimeAndCommitsOneTerminalFact()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(runtime.run(any(), any(), any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            com.agentflow.core.runtime.AgentRunOptions options = invocation.getArgument(2);
            entered.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!options.cancellationSignal().isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(options.cancellationSignal().isCancelled()).isTrue();
            return AgentResult.failure(request.taskId(), RunStatus.CANCELLED,
                    TerminationReason.CANCELLED, "cancelled", List.of(), TokenUsage.empty());
        });

        String taskId = create("cancel me");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        HttpResponse<String> cancel = send("/api/agent/tasks/" + taskId + "/cancel", "POST", null);
        assertThat(cancel.statusCode()).isIn(200, 202);
        Map<String, Object> terminal = awaitTerminal(taskId);
        assertThat(terminal).containsEntry("status", "CANCELLED")
                .containsEntry("terminationReason", "CANCELLED");
        verify(runtime, times(1)).run(any(), any(), any());
    }

    private String create(String input) throws Exception {
        String body = mapper.writeValueAsString(Map.of("input", input));
        HttpResponse<String> response = send("/api/agent/tasks", "POST", body);
        assertThat(response.statusCode()).isEqualTo(202);
        return String.valueOf(mapper.readValue(response.body(), Map.class).get("taskId"));
    }

    private Map<String, Object> awaitTerminal(String taskId) throws Exception {
        Map<String, Object> snapshot = Map.of();
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpResponse<String> response = send("/api/agent/tasks/" + taskId, "GET", null);
            if (response.statusCode() == 503) {
                Thread.sleep(20);
                continue;
            }
            assertThat(response.statusCode()).isEqualTo(200);
            snapshot = mapper.readValue(response.body(), Map.class);
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED")
                    .contains(snapshot.get("status"))) return snapshot;
            Thread.sleep(10);
        }
        throw new AssertionError("run did not reach a terminal state: " + snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String path) throws Exception {
        HttpResponse<String> response = send(path, "GET", null);
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readValue(response.body(), Map.class);
    }

    private List<?> getList(String path) throws Exception {
        HttpResponse<String> response = send(path, "GET", null);
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readValue(response.body(), List.class);
    }

    private HttpResponse<InputStream> events(String taskId, long cursor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/agent/tasks/" + taskId + "/events"))
                .header("Authorization", "Bearer " + token)
                .header("Last-Event-ID", Long.toString(cursor)).timeout(Duration.ofSeconds(5))
                .GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .get(6, TimeUnit.SECONDS);
    }

    private HttpResponse<String> send(String path, String method, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(5));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static BufferedReader reader(HttpResponse<InputStream> response) {
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }
    private static String readEventId(BufferedReader reader) throws Exception {
        String id = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("id:")) id = line.substring(3).trim();
            if (line.isEmpty() && id != null) return id;
        }
        throw new AssertionError("stream ended before event");
    }
    private static List<String> readAllEventIds(BufferedReader reader) throws Exception {
        List<String> ids = new ArrayList<>();
        String id = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("id:")) id = line.substring(3).trim();
            if (line.isEmpty() && id != null) { ids.add(id); id = null; }
        }
        return ids;
    }
}
