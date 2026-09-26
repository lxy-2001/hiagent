package com.agentflow.demo.approval;

import com.agentflow.demo.AgentFlowDemoApplication;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import com.agentflow.web.auth.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Isolated offline demonstration. Normal application code never imports this launcher. */
public final class FixtureDemoLauncher {
    public static void main(String[] args) throws Exception {
        int web = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
        int mcp = args.length > 1 ? Integer.parseInt(args[1]) : 18081;
        var fixture = start(web, mcp);
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close));
        System.out.println("Offline fixture ready on port " + fixture.port + "; login fixture-admin / fixture-only-password");
    }
    public static Fixture start(int webPort, int mcpPort) throws Exception {
        var server = new OfflineMcpFixture(mcpPort);
        try {
            var app = new SpringApplication(AgentFlowDemoApplication.class, FixtureConfiguration.class);
            app.setAdditionalProfiles("fixture");
            var context = app.run("--server.port="+webPort, "--agentflow.mcp.servers[0].url="+server.endpoint(),
                    "--agentflow.mcp.servers[0].api-key="+OfflineMcpFixture.API_SECRET,
                    "--agentflow.mcp.servers[0].id=demo", "--agentflow.mcp.servers[0].allowed-tools=project_info,note_append",
                    "--spring.datasource.url=jdbc:h2:mem:fixture-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            return new Fixture(server, context);
        } catch (Throwable failure) { server.close(); throw failure; }
    }
    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Profile("fixture")
    public static class FixtureConfiguration {
        @Bean AgentModelClient fixtureModel() {
            return request -> request.iteration() == 1
                    ? new ToolCallDecision("fixture-decision", new ToolCall("fixture-call", "mcp.demo.note_append",
                        new ToolArguments(Map.of("title","Java17 demo","body",OfflineMcpFixture.PRIVATE_BODY))),TokenUsage.empty())
                    : new FinalAnswerDecision("fixture-final","Offline note receipt confirmed.",TokenUsage.empty());
        }
        @Bean public static StringRedisTemplate fixtureRedis() {
            var redis = mock(StringRedisTemplate.class);
            ValueOperations<String,String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.increment(anyString())).thenReturn(1L);
            when(redis.hasKey(anyString())).thenReturn(false);
            return redis;
        }
    }
    public static final class FixtureHttpException extends RuntimeException {
        final int status;
        FixtureHttpException(int status, String code) { super("fixture HTTP " + status + " " + code); this.status=status; }
    }
    public static final class Fixture implements AutoCloseable {
        private final OfflineMcpFixture server;
        private final ConfigurableApplicationContext context;
        private final int port;
        private final String token;
        private final ObjectMapper json = new ObjectMapper();
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Fixture(OfflineMcpFixture server, ConfigurableApplicationContext context) {
            this.server=server; this.context=context;
            port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            token=context.getBean(JwtService.class).issueAccessToken("fixture-owner","fixture-owner",List.of("USER")).value();
        }
        public OfflineMcpFixture server() { return server; }
        public ConfigurableApplicationContext context() { return context; }
        public JsonNode request(String method,String path,Object body) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var result=http.send(request,HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() < 200 || result.statusCode() >= 300) throw new FixtureHttpException(result.statusCode(), json.readTree(result.body()).path("code").asString());
            return json.readTree(result.body());
        }
        public String events(String task) throws Exception {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/agent/tasks/"+task+"/events"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token)
                    .header("Accept","text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new AssertionError("fixture SSE status "+response.statusCode());
            return response.body();
        }
        public JsonNode awaitApproval(String task) throws Exception {
            long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(System.nanoTime()<end) {
                var list=request("GET","/api/agent/tasks/"+task+"/approvals",null).path("items");
                if (!list.isEmpty()) return list.get(0);
                Thread.sleep(25);
            }
            throw new AssertionError("approval not created");
        }
        public JsonNode awaitTerminal(String task) throws Exception {
            long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(System.nanoTime()<end) {
                try {
                    var run=request("GET","/api/agent/tasks/"+task,null);
                    if (!run.path("finishedAt").isNull()) return run;
                } catch (FixtureHttpException unavailable) {
                    if (unavailable.status != 503) throw unavailable;
                    // A read during FINAL_PENDING is temporarily unavailable; only repeat this GET.
                }
                Thread.sleep(25);
            }
            throw new AssertionError("run did not finish");
        }
        @Override public void close() { try { context.close(); } finally { server.close(); } }
    }
}
