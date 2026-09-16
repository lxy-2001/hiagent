package com.agentflow.web.run;

import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = RunSseReconnectTest.TestApplication.class)
class RunSseReconnectTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired InMemoryRunEventHub hub;
    @org.springframework.beans.factory.annotation.Autowired AgentTaskService tasks;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void setUp() {
        reset(tasks);
        hub.closeSubscriptions();
        hub.maintain();
    }

    @Test
    void embeddedHttpServerSupportsLateSubscribersDisconnectAndCursorReconnect() throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        hub.create("task");
        when(tasks.getTask(eq("owner"), eq("task"))).thenReturn(snapshot(now, false));
        publish(RunEvent.Type.RUN_CREATED);
        publish(RunEvent.Type.RUN_STARTED);

        HttpResponse<InputStream> first = open(0);
        HttpResponse<InputStream> late = open(1);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(late.statusCode()).isEqualTo(200);
        BufferedReader firstEvents = reader(first);
        BufferedReader lateEvents = reader(late);
        assertThat(readEventId(firstEvents)).isEqualTo("1");
        assertThat(readEventId(firstEvents)).isEqualTo("2");
        assertThat(readEventId(lateEvents)).isEqualTo("2");

        first.body().close();
        publish(RunEvent.Type.AGENT_STEP);

        HttpResponse<InputStream> reconnect = open(2);
        assertThat(reconnect.statusCode()).isEqualTo(200);
        BufferedReader reconnectedEvents = reader(reconnect);
        assertThat(readEventId(reconnectedEvents)).isEqualTo("3");
        assertThat(readEventId(lateEvents)).isEqualTo("3");

        publish(RunEvent.Type.RUN_TERMINATED);
        hub.markTerminal("task");
        when(tasks.getTask(eq("owner"), eq("task"))).thenReturn(snapshot(now, true));
        assertThat(readEventId(reconnectedEvents)).isEqualTo("4");
        assertThat(readEventId(lateEvents)).isEqualTo("4");
        assertThat(reconnectedEvents.readLine()).isNull();
        assertThat(lateEvents.readLine()).isNull();

        HttpResponse<InputStream> finished = open(4);
        assertThat(finished.statusCode()).isEqualTo(204);
    }

    private HttpResponse<InputStream> open(long cursor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/agent/tasks/task/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Last-Event-ID", Long.toString(cursor)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .get(6, TimeUnit.SECONDS);
    }

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
        throw new AssertionError("SSE stream ended before the next event");
    }

    private void publish(RunEvent.Type type) {
        hub.publish("task", new RunEvent.Draft("task", "task", type,
                Instant.parse("2026-09-15T00:00:00Z"), Map.of("type", type.name())));
    }

    private static RunSnapshot snapshot(Instant now, boolean terminal) {
        RunLifecycleStatus status = terminal ? RunLifecycleStatus.SUCCEEDED : RunLifecycleStatus.RUNNING;
        return new RunSnapshot("task", "task", "session", status, "input",
                terminal ? "done" : null, now, now, now, terminal ? now : null, false,
                terminal ? RunTerminationReason.COMPLETED : null,
                terminal ? com.agentflow.core.runtime.TerminationReason.COMPLETED : null,
                null, false, terminal ? com.agentflow.core.chat.TokenUsage.empty() : null);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
            "com.agentflow.web.autoconfigure.AgentWebAutoConfiguration"})
    @Import({AgentController.class, RunSseMvcConfiguration.class})
    static class TestApplication {
        @Bean SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
        }
        @Bean AgentTaskService taskService() { return mock(AgentTaskService.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean RunLifecycleProperties properties() {
            return new RunLifecycleProperties(1, 1, 2, Duration.ofSeconds(30),
                    256, 1_048_576, 16_384, 8, Duration.ofMinutes(10), 3, 3, 3);
        }
        @Bean InMemoryRunEventHub eventHub(ObjectMapper mapper, RunLifecycleProperties properties) {
            return new InMemoryRunEventHub(mapper, properties.eventWindowCount(),
                    properties.eventWindowBytes(), properties.eventFrameBytes(),
                    properties.inFlightCapacity(), properties.terminalCacheCapacity(),
                    properties.terminalCacheTtl().toNanos(), System::nanoTime,
                    properties.subscriptionsPerRun(),
                    properties.globalSubscriptions());
        }
        @Bean RunSseService sseService(InMemoryRunEventHub hub,
                                      RunLifecycleProperties properties, Clock clock) {
            return new RunSseService(hub, properties, clock);
        }
        @Bean WebMvcConfigurer jwtPrincipalResolver(Clock clock) {
            return new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new HandlerMethodArgumentResolver() {
                        @Override public boolean supportsParameter(MethodParameter parameter) {
                            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                                    && parameter.getParameterType() == Jwt.class;
                        }
                        @Override public Object resolveArgument(MethodParameter parameter,
                                ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
                                org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                            Instant issued = clock.instant();
                            return Jwt.withTokenValue("test-token").header("alg", "none")
                                    .subject("owner").issuedAt(issued)
                                    .expiresAt(issued.plusSeconds(300)).build();
                        }
                    });
                }
            };
        }
    }
}
