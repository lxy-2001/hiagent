package com.agentflow.demo.evaluation;

import com.agentflow.core.*;
import com.agentflow.core.context.*;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.*;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.beans.factory.config.BeanPostProcessor;
import com.agentflow.web.run.RunLifecycleProperties;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;


@TestConfiguration(proxyBeanMethods = false)
@org.springframework.context.annotation.Profile("evaluation-fixture")
public class EvaluationFixtureApplication {
    static final class State {
        final String scenario;
        final int window;
        final ConcurrentMap<String, AgentResult> results = new ConcurrentHashMap<>();
        final ConcurrentMap<String, List<AgentEvent>> events = new ConcurrentHashMap<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<AgentModelRequest> requests = new CopyOnWriteArrayList<>();
        ObservedModelClient model;
        State(String scenario, int window) { this.scenario = scenario; this.window = window; }
    }
    @Bean AgentModelClient evaluationModel(State state) {
        state.model = new ObservedModelClient(request -> {
            state.requests.add(request);
            if (state.scenario.equals("capacity") && request.input().equals("hold")) {
                state.entered.countDown();
                try { if (!state.release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("FIXTURE_DEADLINE"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("FIXTURE_CANCELLED"); }
            }
            if (Set.of("approval-approved", "approval-rejected", "cancel-waiting").contains(state.scenario)) {
                if (request.iteration() == 1) return new ToolCallDecision("fixture-decision", new ToolCall("fixture-call", "mcp.demo.note_append",
                        new ToolArguments(Map.of("title", "evaluation", "body", "public fixture note"))), TokenUsage.empty(), UsageSource.FIXTURE);
                boolean receipt = request.messages().stream().anyMatch(m -> m.role().equals("tool") && m.content().contains("fixture-receipt-1"));
                return new FinalAnswerDecision("fixture-final", receipt ? "RECEIPT_CONFIRMED" : "RECEIPT_MISSING", TokenUsage.empty(), UsageSource.FIXTURE);
            }
            return new FinalAnswerDecision("fixture-final", "OK", TokenUsage.empty(), UsageSource.FIXTURE);
        });
        return state.model;
    }
    @Bean ObservingContextAssembler evaluationAssembler(State state) {
        return new ObservingContextAssembler(new ContextPolicy(ContextPolicy.DEFAULT_SYSTEM, ContextPolicy.defaults().promptVersion(), state.window));
    }
    @Bean AgentRuntime evaluationRuntime(AgentModelClient model, ToolRegistry registry, ToolExecutor executor, StepRecorder recorder,
                                         ToolResultNormalizer normalizer, ObservingContextAssembler assembler, ToolExecutionPolicy policy, State state) {
        var runtime = new DefaultAgentRuntime(model, registry, executor, recorder, normalizer, TimeSource.system(), assembler, policy);
        return (request, sink, options) -> {
            var events = new CopyOnWriteArrayList<AgentEvent>(); state.events.put(request.taskId(), events);
            var result = runtime.run(request, event -> { events.add(event); sink.publish(event); }, options);
            state.results.put(request.taskId(), result); return result;
        };
    }
    @Bean static BeanPostProcessor evaluationContextFailure(State state) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof ContextSource && state.scenario.equals("context-source-failure")) {
                    return (ContextSource) (query, timeout, cancellation) -> { throw new ContextSource.ContextSourceException("FIXTURE_SOURCE_FAILURE"); };
                }
                return bean;
            }
        };
    }
    @Bean RunLifecycleProperties evaluationLifecycle() {
        var d = RunLifecycleProperties.defaults();
        return new RunLifecycleProperties(1, 1, 2, Duration.ofSeconds(15), d.eventWindowCount(), d.eventWindowBytes(),
                d.eventFrameBytes(), d.terminalCacheCapacity(), d.terminalCacheTtl(), d.subscriptionsPerRun(), d.globalSubscriptions(),
                d.senderThreads(), Duration.ofSeconds(15));
    }
    @Bean StringRedisTemplate evaluationRedis() {
        return new StringRedisTemplate() {
            @Override public void afterPropertiesSet() { }
            @Override public Boolean hasKey(String key) { return false; }
            @Override public Boolean expire(String key, Duration timeout) { return true; }
            @Override public Boolean delete(String key) { return true; }
            @Override public ValueOperations<String, String> opsForValue() {
                @SuppressWarnings("unchecked")
                var values = (ValueOperations<String, String>) java.lang.reflect.Proxy.newProxyInstance(
                        ValueOperations.class.getClassLoader(), new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                            if (method.getName().equals("increment")) return 1L;
                            if (method.getName().equals("set")) return null;
                            throw new UnsupportedOperationException("Unexpected fixture Redis operation: " + method.getName());
                        });
                return values;
            }
        };
    }
}
