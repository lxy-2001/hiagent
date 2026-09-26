package com.agentflow.demo.delivery;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.*;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.tool.*;
import com.agentflow.demo.AgentFlowDemoApplication;
import com.agentflow.demo.approval.FixtureDemoLauncher;
import com.agentflow.demo.approval.OfflineMcpFixture;
import com.agentflow.demo.evaluation.FixedEvidenceRetriever;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicit test-classpath launcher. Never loaded by a production application. */
public final class DeliveryDemoLauncher implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<AgentModelRequest> requests = new CopyOnWriteArrayList<>();
    private final String scenario;
    private OfflineMcpFixture mcp;
    private ConfigurableApplicationContext context;
    private String base, token;

    private DeliveryDemoLauncher(String scenario) { this.scenario = scenario; }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("--offline")) throw new IllegalArgumentException("Explicit --offline is required");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 18082;
        var fixture = start("happy-path", port);
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close));
        System.out.println("OFFLINE_FIXTURE " + fixture.base + " login: fixture-admin / fixture-only-password; inputs: remember, research, note");
    }

    public static DeliveryDemoLauncher start(String scenario, int port) throws Exception {
        if (!Set.of("happy-path", "rejected", "cancelled", "empty-evidence", "dependency-failure").contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        var fixture = new DeliveryDemoLauncher(scenario);
        try {
            fixture.mcp = new OfflineMcpFixture(0);
            var app = new SpringApplication(AgentFlowDemoApplication.class, Configuration.class);
            app.setAdditionalProfiles("fixture", "delivery-fixture");
            app.addInitializers(c -> c.getBeanFactory().registerSingleton("deliveryFixture", fixture));
            fixture.context = app.run("--server.address=127.0.0.1", "--server.port=" + port, "--logging.level.root=ERROR",
                    "--spring.main.banner-mode=off", "--server.shutdown=immediate", "--spring.lifecycle.timeout-per-shutdown-phase=1s",
                    "--spring.datasource.url=jdbc:h2:mem:delivery-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0",
                    "--spring.datasource.username=sa", "--spring.datasource.password=", "--agentflow.rag.enabled=true",
                    "--agentflow.knowledge.bootstrap.enabled=false", "--agentflow.initial-admin.username=fixture-admin",
                    "--agentflow.initial-admin.password=fixture-only-password", "--agentflow.security.jwt.secret=fixture-only-delivery-secret-not-production-008",
                    "--agentflow.mcp.servers[0].id=demo", "--agentflow.mcp.servers[0].allowed-tools=project_info,note_append",
                    "--agentflow.mcp.servers[0].api-key=" + OfflineMcpFixture.API_SECRET,
                    "--agentflow.mcp.servers[0].url=" + fixture.mcp.endpoint());
            fixture.base = "http://127.0.0.1:" + fixture.context.getEnvironment().getProperty("local.server.port");
            fixture.token = fixture.request("POST", "/api/auth/login", Map.of("username", "fixture-admin", "password", "fixture-only-password")).path("accessToken").asString();
            return fixture;
        } catch (Throwable failure) { fixture.close(); throw failure; }
    }
    JsonNode create(String input, String session, boolean evidence) throws Exception {
        var body = new HashMap<String, Object>(); body.put("input", input); body.put("requireEvidence", evidence);
        if (session != null) body.put("sessionId", session);
        return request("POST", "/api/agent/tasks", body);
    }
    HttpResponse<String> send(String method, String path, Object body, String cursor) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (cursor != null) builder.header("Last-Event-ID", cursor);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode request(String method, String path, Object body) throws Exception {
        var response = send(method, path, body, null);
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Fixture HTTP " + response.statusCode());
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
    JsonNode awaitTerminal(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var response = send("GET", "/api/agent/tasks/" + id, null, null);
            if (response.statusCode() == 503) { Thread.sleep(15); continue; }
            if (response.statusCode() != 200) throw new IllegalStateException("Snapshot unavailable");
            var snapshot = json.readTree(response.body());
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED").contains(snapshot.path("status").asString())) return snapshot;
            Thread.sleep(15);
        }
        throw new IllegalStateException("Run deadline");
    }
    JsonNode awaitApproval(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var items = request("GET", "/api/agent/tasks/" + id + "/approvals", null).path("items");
            if (!items.isEmpty()) return items.get(0);
            Thread.sleep(15);
        }
        throw new IllegalStateException("Approval deadline");
    }
    void decide(String id, String approval, String decision) throws Exception {
        request("POST", "/api/agent/tasks/" + id + "/approvals/" + approval + "/decision", Map.of("decision", decision));
    }
    List<AgentModelRequest> requests(String id) { return requests.stream().filter(r -> r.taskId().equals(id)).toList(); }
    int writes() { return mcp.writes(); }
    int port() { return URI.create(base).getPort(); }
    HttpResponse<String> events(String id, String cursor) throws Exception { return send("GET", "/api/agent/tasks/" + id + "/events", null, cursor); }
    void evidence(String scenario, String id, JsonNode done) throws Exception {
        Path output = Path.of("target", "delivery"); Files.createDirectories(output);
        var evidence = new LinkedHashMap<String, Object>();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("agent-demo/pom.xml"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Source root unavailable");
        String manifest = System.getProperty("agentflow.eval.provenance");
        var source = com.agentflow.eval.CodeProvenance.read(root, manifest == null ? null : Path.of(manifest), List.of("pom.xml",
                "agent-demo/src/test/java/com/agentflow/demo/delivery/DeliveryDemoLauncher.java"));
        evidence.put("codeSha", source.codeSha()); evidence.put("codeStatus", source.status()); evidence.put("workingTreeDirty", source.workingTreeDirty());
        evidence.put("citationIds", java.util.stream.StreamSupport.stream(done.path("citations").spliterator(), false)
                .map(c -> Map.of("id", c.path("id").asString(), "chunkId", c.path("chunkId").asString(), "snapshotId", c.path("snapshotId").asString())).toList());
        evidence.put("mode", "OFFLINE_FIXTURE"); evidence.put("scenario", scenario); evidence.put("runId", id);
        evidence.put("status", done.path("status").asString()); evidence.put("terminationReason", done.path("terminationReason").asString());
        evidence.put("writes", writes()); evidence.put("modelAttempts", requests(id).size());
        evidence.put("citations", done.path("citations").size()); evidence.put("recordingComplete", done.path("recordingComplete").asBoolean());
        Files.writeString(output.resolve(scenario + ".json"), json.writeValueAsString(evidence));
    }
    @Override public void close() {
        try { if (context != null) context.close(); } finally { if (mcp != null) mcp.close(); }
    }

    @TestConfiguration(proxyBeanMethods = false) @Profile("delivery-fixture")
    public static class Configuration {
        @Bean RagRetriever deliveryRetriever(DeliveryDemoLauncher fixture) {
            return new FixedEvidenceRetriever(fixture.scenario.equals("dependency-failure") ? "retrieval-failure" : fixture.scenario);
        }
        @Bean StringRedisTemplate deliveryRedis() { return FixtureDemoLauncher.FixtureConfiguration.fixtureRedis(); }
        @Bean AgentModelClient deliveryModel(DeliveryDemoLauncher fixture) {
            return request -> {
                fixture.requests.add(request);
                if (request.input().equals("remember")) return answer("Remembered conversation", "remember");
                if (request.input().equals("research")) {
                    if (request.iteration() == 1) return call("knowledge.search", Map.of("query", "Java module interfaces"));
                    boolean evidence = request.messages().stream().anyMatch(m -> m.role().equals("tool") && m.content().contains("[S1]"));
                    return answer(evidence ? "Java interfaces define module contracts [S1]." : "No sources available.", "research");
                }
                if (request.iteration() == 1) return call("mcp.demo.note_append", Map.of("title", "Java study", "body", "Synthetic Java module note"));
                boolean receipt = request.messages().stream().anyMatch(m -> m.role().equals("tool") && m.content().contains("fixture-receipt-1"));
                return answer(receipt ? "RECEIPT_CONFIRMED" : "RECEIPT_MISSING", "note");
            };
        }
        private static ModelDecision call(String name, Map<String, Object> args) {
            return new ToolCallDecision("fixture-call-decision", new ToolCall("fixture-call", name, new ToolArguments(args)), TokenUsage.empty(), UsageSource.FIXTURE);
        }
        private static ModelDecision answer(String text, String id) { return new FinalAnswerDecision(id, text, TokenUsage.empty(), UsageSource.FIXTURE); }
    }
}
