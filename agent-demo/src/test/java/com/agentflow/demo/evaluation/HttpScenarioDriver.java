package com.agentflow.demo.evaluation;

import com.agentflow.demo.AgentFlowDemoApplication;
import com.agentflow.demo.approval.OfflineMcpFixture;
import com.agentflow.eval.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Case-owned real HTTP application; business setup uses authenticated public APIs. */
final class HttpScenarioDriver implements ScenarioDriver {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private OfflineMcpFixture mcp;
    private EvaluationFixtureApplication.State state;
    private int port;
    private String token;
    private final List<String> supporting = new ArrayList<>();
    private ConfigurableApplicationContext start(String database, String user) {
        var app = new SpringApplication(AgentFlowDemoApplication.class, EvaluationFixtureApplication.class);
        app.setAdditionalProfiles("fixture", "evaluation-fixture");
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("evaluationState", state));
        var context = app.run("--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                "--agentflow.rag.enabled=false", "--agentflow.knowledge.bootstrap.enabled=false",
                "--agentflow.initial-admin.password=fixture-only-password", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--agentflow.security.jwt.secret=fixture-only-secret-not-for-production-007", "--logging.level.root=ERROR", "--server.shutdown=immediate", "--spring.lifecycle.timeout-per-shutdown-phase=500ms",
                "--spring.datasource.hikari.minimum-idle=1", "--spring.datasource.hikari.maximum-pool-size=2", "--agentflow.initial-admin.username=" + user,
                "--agentflow.mcp.servers[0].url=" + mcp.endpoint(),
                "--agentflow.mcp.servers[0].api-key=" + OfflineMcpFixture.API_SECRET,
                "--agentflow.mcp.servers[0].id=demo", "--agentflow.mcp.servers[0].allowed-tools=project_info,note_append",
                "--spring.datasource.url=" + database);
        contexts.add(context); port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return context;
    }
    static void prepareInfrastructure() throws Exception {
        // Class loading and framework bootstrapping precede the measured cases; no business Run is executed.
        // This context is closed completely; every case receives its own database, server and observations.
        try (var driver = new HttpScenarioDriver()) {
            driver.state = new EvaluationFixtureApplication.State("bootstrap", 16384);
            driver.mcp = new OfflineMcpFixture(0);
            driver.start("jdbc:h2:mem:preflight-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0", "fixture-admin");
        }
    }    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(OfflineConnections.requireLoopback(URI.create("http://127.0.0.1:" + port + path))).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode request(String method, String path, Object body) throws Exception {
        var response = send(method, path, body);
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Fixture HTTP " + response.statusCode() + " " + response.body());
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
    private String login(String user) throws Exception {
        return request("POST", "/api/auth/login", Map.of("username", user, "password", "fixture-only-password")).path("accessToken").asString();
    }
    private JsonNode create(String input, String session) throws Exception {
        var body = new HashMap<String, Object>(); body.put("input", input);
        if (session != null) body.put("sessionId", session);
        return request("POST", "/api/agent/tasks", body);
    }
    private JsonNode await(String id, boolean approval) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < end) {
            var response = send("GET", "/api/agent/tasks/" + id, null);
            if (response.statusCode() == 503) { Thread.sleep(10); continue; }
            if (response.statusCode() != 200) throw new IllegalStateException("Snapshot HTTP " + response.statusCode());
            var snapshot = json.readTree(response.body());
            String status = snapshot.path("status").asString();
            if (approval ? status.equals("WAITING_APPROVAL") : !Set.of("QUEUED", "RUNNING", "WAITING_APPROVAL").contains(status)) return snapshot;
            Thread.sleep(10);
        }
        throw new IllegalStateException("Fixture run deadline");
    }
    @Override public ObservedCase execute(EvalCase definition, EvalVariant variant, int repeat) throws Exception {
        state = new EvaluationFixtureApplication.State(definition.fixtureId(), variant.contextWindow());
        mcp = new OfflineMcpFixture(0);
        String database = "jdbc:h2:mem:eval-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        var context = start(database, "fixture-admin");
        String otherToken = null;
        if (definition.fixtureId().equals("memory-update")) {
            context = start(database, "fixture-other");
            otherToken = login("fixture-other");
        }
        token = login("fixture-admin");
        var facts = new EnumMap<EvalCase.Rule, ObservedCase.Fact>(EvalCase.Rule.class);
        var created = create(definition.fixtureId().equals("capacity") ? "hold" : definition.input(), null);
        String id = created.path("taskId").asString();
        if (Set.of("approval-approved", "approval-rejected", "cancel-waiting").contains(definition.fixtureId())) {
            await(id, true);
            boolean zeroBeforeApproval = mcp.writes() == 0;
            var approvals = request("GET", "/api/agent/tasks/" + id + "/approvals", null);
            String approvalId = approvals.path("items").get(0).path("approvalId").asString();
            String path = "/api/agent/tasks/" + id + "/approvals/" + approvalId + "/decision";
            if (definition.fixtureId().equals("cancel-waiting")) request("POST", "/api/agent/tasks/" + id + "/cancel", null);
            else {
                String decision = definition.fixtureId().equals("approval-approved") ? "APPROVE" : "REJECT";
                request("POST", path, Map.of("decision", decision));
                request("POST", path, Map.of("decision", decision));
            }
            await(id, false);
            int expectedWrites = definition.fixtureId().equals("approval-approved") ? 1 : 0;
            facts.put(EvalCase.Rule.WRITE_COUNT, new ObservedCase.Fact(List.of(zeroBeforeApproval, mcp.writes()), List.of(true, expectedWrites)));
            facts.put(EvalCase.Rule.ARGUMENTS_MATCH, new ObservedCase.Fact(mcp.lastBody(), "public fixture note"));
        } else if (definition.fixtureId().equals("capacity")) {
            if (!state.entered.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Worker did not enter fixture");
            String queued = create("queued", null).path("taskId").asString(); supporting.add(queued);
            var rejection = send("POST", "/api/agent/tasks", Map.of("input", "rejected"));
            facts.put(EvalCase.Rule.HTTP_CAPACITY, new ObservedCase.Fact(List.of(rejection.statusCode(),
                    json.readTree(rejection.body()).path("code").asString(), rejection.headers().firstValue("Location").isEmpty()),
                    List.of(429, "RUN_CAPACITY_EXCEEDED", true)));
            state.release.countDown(); await(queued, false);
        } else if (definition.fixtureId().equals("memory-update")) {
            await(id, false); supporting.add(id);
            String session = created.path("sessionId").asString();
            String path = "/api/agent/sessions/" + session + "/memories";
            var first = request("PUT", path + "/project_stack", Map.of("value", "Java17", "expectedVersion", "0"));
            request("PUT", path + "/project_stack", Map.of("value", "Java21", "expectedVersion", first.path("version").asString()));
            String ownerToken = token; token = otherToken;
            var denied = send("GET", path, null); token = ownerToken;
            facts.put(EvalCase.Rule.OWNER_ISOLATION, new ObservedCase.Fact(denied.statusCode(), 404));
            id = create("Use my current project stack", session).path("taskId").asString();
            await(id, false);
            var last = state.requests.get(state.requests.size() - 1);
            String messages = last.messages().toString();
            facts.put(EvalCase.Rule.MEMORY_CURRENT, new ObservedCase.Fact(List.of(messages.contains("Java21"), messages.contains("Java17")), List.of(true, false)));
        }
        var snapshot = await(id, false);
        var result = state.results.get(id);
        String answer = snapshot.path("finalAnswer").asString("");
        facts.put(EvalCase.Rule.ANSWER_MARKER, new ObservedCase.Fact(answer, definition.fixtureId().equals("approval-approved") ? "RECEIPT_CONFIRMED" : "OK"));
        facts.put(EvalCase.Rule.NO_SECRET, new ObservedCase.Fact(snapshot.toString().contains(OfflineMcpFixture.API_SECRET), false));
        var steps = new ArrayList<ObservedCase.StepFact>();
        for (var step : request("GET", "/api/agent/tasks/" + id + "/steps", null)) steps.add(new ObservedCase.StepFact(
                step.path("stepNo").asInt(), step.path("terminal").asBoolean(), step.path("stepType").asString(),
                step.path("status").asString(), step.path("toolName").asString(), step.path("callId").asString(), step.path("errorCode").asString()));
        var lifecycle = new ArrayList<ObservedCase.LifecycleEvent>();
        var stream = send("GET", "/api/agent/tasks/" + id + "/events", null);
        if (stream.statusCode() != 200) throw new IllegalStateException("SSE unavailable");
        for (String line : stream.body().lines().toList()) if (line.startsWith("data:")) {
            var event = json.readTree(line.substring(5).trim());
            lifecycle.add(new ObservedCase.LifecycleEvent(event.path("runId").asString(), Long.parseLong(event.path("eventId").asString()),
                    event.path("type").asString(), event.path("payload").path("status").asString(), event.path("payload").path("terminationReason").asString()));
        }
        return new ObservedCase(result, state.events.getOrDefault(id, List.of()), context.getBean(ObservingContextAssembler.class).diagnostics(id),
                state.model.attempts(id), webEvidenceComplete(lifecycle, id) && (result == null || snapshot.path("recordingComplete").asBoolean()
                        && steps.size() == result.steps().size() && snapshot.path("status").asString().equals(result.status().name())),
                mcp.calls(), facts,
                snapshot.path("status").asString(), snapshot.path("terminationReason").asString(),
                new ObservedCase.WebTrace(id, steps, snapshot.path("recordingComplete").asBoolean(), lifecycle, state.requests.size()), metrics(snapshot, result), supporting);
    }
    private static boolean webEvidenceComplete(List<ObservedCase.LifecycleEvent> events, String runId) {
        if (events.isEmpty() || !events.get(0).type().equals("RUN_CREATED") || !events.get(events.size() - 1).type().equals("RUN_TERMINATED")) return false;
        long next = 1; int terminals = 0;
        for (var event : events) {
            if (event.sequence() != next++ || !runId.equals(event.runId())) return false;
            if (event.type().equals("RUN_TERMINATED")) terminals++;
        }
        return terminals == 1;
    }
    private Map<String, EvaluationMetrics.Metric> metrics(JsonNode snapshot, com.agentflow.core.AgentResult result) {
        var metrics = new HashMap<String, EvaluationMetrics.Metric>();
        for (var pair : Map.of("queueWait", List.of("createdAt", "startedAt", "QUEUE"),
                "runtimeTotal", List.of("startedAt", "finishedAt", "WEB_ACTIVE")).entrySet()) {
            var fields = pair.getValue();
            if (snapshot.path(fields.get(0)).isTextual() && snapshot.path(fields.get(1)).isTextual()) {
                double millis = Duration.between(Instant.parse(snapshot.path(fields.get(0)).asString()), Instant.parse(snapshot.path(fields.get(1)).asString())).toNanos() / 1_000_000.0;
                metrics.put(pair.getKey(), new EvaluationMetrics.Metric("KNOWN", millis, "ms", fields.get(2)));
            }
        }
        var attempts = state.model.attempts(snapshot.path("taskId").asString());
        metrics.put("modelCall", attempts.isEmpty() ? new EvaluationMetrics.Metric("NOT_APPLICABLE", null, "ms", "MODEL")
                : new EvaluationMetrics.Metric("KNOWN", attempts.stream().mapToDouble(EvaluationMetrics.Attempt::elapsedMillis).sum(), "ms", "MODEL"));
        if (result != null) {
            metrics.put("approvalWait", CoreScenarioDriver.duration(result.toolInvocations().stream().filter(c -> c.approvalId() != null).map(com.agentflow.core.tool.ToolInvocationRecord::approvalWaitMillis).toList(), "APPROVAL"));
            metrics.put("toolExecution", CoreScenarioDriver.duration(result.toolInvocations().stream().filter(c -> c.dispatchCount() > 0).map(com.agentflow.core.tool.ToolInvocationRecord::executionMillis).toList(), "TOOL"));
        }
        return metrics;
    }
    @Override public void close() {
        if (state != null) state.release.countDown();
        for (int i = contexts.size() - 1; i >= 0; i--) contexts.get(i).close();
        if (mcp != null) mcp.close();
    }
}
