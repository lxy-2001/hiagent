package com.agentflow.demo.evaluation;

import com.agentflow.core.*;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.runtime.*;
import com.agentflow.core.tool.*;
import com.agentflow.eval.*;
import com.agentflow.llm.*;
import com.agentflow.tool.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** Opt-in live model only; tools remain deterministic local reads, never MCP or external RAG. */
final class LiveEvaluationDriver implements ScenarioDriver {
    static final class Budget implements AgentModelClient {
        private final AgentModelClient delegate;
        private final LongSupplier clock;
        private final long started;
        private int calls;
        Budget(AgentModelClient delegate, LongSupplier clock) { this.delegate = delegate; this.clock = clock; this.started = clock.getAsLong(); }
        synchronized boolean available() { return calls < 5 && !remaining().isZero(); }
        Duration remaining() { return Duration.ofNanos(Math.max(0, Duration.ofSeconds(60).toNanos() - (clock.getAsLong() - started))); }
        @Override public ModelDecision decide(AgentModelRequest request) {
            synchronized (this) {
                if (!available()) throw new IllegalStateException("LIVE_BUDGET_EXHAUSTED");
                calls++;
            }
            int output = request.maxCompletionTokens() == null ? 512 : Math.min(512, request.maxCompletionTokens());
            if (output <= 0) throw new IllegalStateException("LIVE_OUTPUT_BUDGET_EXHAUSTED");
            return delegate.decide(new AgentModelRequest(request.taskId(), request.sessionId(), request.userId(), request.input(),
                    request.messages(), request.tools(), request.iteration(), output));
        }
    }
    static Budget connect(Map<String, String> environment, boolean allowPaid) {
        if (!allowPaid) throw new IllegalArgumentException("PAID_MODEL_NOT_AUTHORIZED");
        for (String name : List.of("PROVIDER", "BASE_URL", "API_KEY", "CHAT_MODEL")) {
            String value = environment.get("AGENTFLOW_MODEL_" + name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("CONFIGURATION_MISSING");
        }
        URI endpoint;
        try { endpoint = URI.create(environment.get("AGENTFLOW_MODEL_BASE_URL")); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("CONFIGURATION_INVALID"); }
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null)
            throw new IllegalArgumentException("CONFIGURATION_INVALID");
        var properties = new AgentFlowProperties();
        properties.model().setProvider(environment.get("AGENTFLOW_MODEL_PROVIDER"));
        properties.model().setBaseUrl(endpoint.toString()); properties.model().setApiKey(environment.get("AGENTFLOW_MODEL_API_KEY"));
        properties.model().setChatModel(environment.get("AGENTFLOW_MODEL_CHAT_MODEL"));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        return new Budget(request -> {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("LIVE_BUDGET_EXHAUSTED");
            var factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofNanos(Math.min(Duration.ofSeconds(15).toNanos(), remaining)));
            return new OpenAiAgentModelClient(new OpenAiCompatibleModelClient(properties, RestClient.builder().requestFactory(factory))).decide(request);
        }, System::nanoTime);
    }
    private final Budget budget;
    LiveEvaluationDriver(Budget budget) { this.budget = budget; }
    @Override public ObservedCase execute(EvalCase scenario, EvalVariant variant, int repeat) {
        if (!Set.of("C01", "C02").contains(scenario.id())) throw new IllegalArgumentException("LIVE_CASE_NOT_ALLOWED");
        var names = new ArrayList<String>(); var arguments = new ArrayList<Map<String, Object>>();
        AgentTool uppercase = new UppercaseTextTool();
        var registry = new InMemoryToolRegistry(List.of(new AgentTool() {
            public ToolDefinition definition() { return uppercase.definition(); }
            public ToolResult execute(ToolArguments input, ToolContext control) {
                names.add(definition().name()); arguments.add(input.values()); return uppercase.execute(input, control);
            }
        }));
        var model = new ObservedModelClient(budget);
        var assembler = new ObservingContextAssembler(new ContextPolicy(ContextPolicy.DEFAULT_SYSTEM, ContextPolicy.defaults().promptVersion(), variant.contextWindow()));
        var policy = ToolExecutionPolicy.rules(Map.of("uppercase-text", new ToolPolicyDecision(ToolPolicyDecision.Action.ALLOW,
                RiskLevel.LOW, ToolPolicyDecision.Effect.READ_ONLY, "Read uppercase text", Set.of())));
        var runtime = new DefaultAgentRuntime(model, registry, new DefaultToolExecutor(registry), null, new DefaultToolResultNormalizer(), TimeSource.system(), assembler, policy);
        var events = new ArrayList<AgentEvent>();
        boolean tool = scenario.id().equals("C02");
        String input = tool ? "Call uppercase-text exactly once with text hiagent. Then reply only with the tool result." : "Reply with exactly OK. Do not call tools.";
        var remaining = budget.remaining();
        if (remaining.isZero()) throw new IllegalStateException("LIVE_BUDGET_EXHAUSTED");
        var duration = remaining.compareTo(Duration.ofSeconds(15)) < 0 ? remaining : Duration.ofSeconds(15);
        long start = System.nanoTime();
        var result = runtime.run(new AgentRequest(UUID.randomUUID().toString(), "live-session", "live-owner", input), events::add,
                new AgentRunOptions(new ExecutionBudget(5, duration, 8192, 2048), () -> Thread.currentThread().isInterrupted() || budget.remaining().isZero()));
        var facts = new EnumMap<EvalCase.Rule, ObservedCase.Fact>(EvalCase.Rule.class);
        facts.put(EvalCase.Rule.ANSWER_MARKER, new ObservedCase.Fact(result.finalAnswer(), tool ? "HIAGENT" : "OK"));
        facts.put(EvalCase.Rule.TOOL_SEQUENCE, new ObservedCase.Fact(names, tool ? List.of("uppercase-text") : List.of()));
        facts.put(EvalCase.Rule.ARGUMENTS_MATCH, new ObservedCase.Fact(arguments, tool ? List.of(Map.of("text", "hiagent")) : List.of()));
        facts.put(EvalCase.Rule.NO_SECRET, new ObservedCase.Fact(result.toolInvocations().stream().allMatch(c -> c.toolName().equals("uppercase-text")), true));
        var metrics = Map.of("runtimeTotal", new EvaluationMetrics.Metric("KNOWN", (System.nanoTime() - start) / 1_000_000.0, "ms", "CORE"),
                "modelCall", new EvaluationMetrics.Metric("KNOWN", model.attempts().stream().mapToDouble(EvaluationMetrics.Attempt::elapsedMillis).sum(), "ms", "MODEL"));
        return new ObservedCase(result, events, assembler.diagnostics(), model.attempts(), true, names.size(), facts, null, null, null, metrics, List.of());
    }
    @Override public void close() { }
}
