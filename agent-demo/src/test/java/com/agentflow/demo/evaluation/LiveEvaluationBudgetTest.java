package com.agentflow.demo.evaluation;

import com.agentflow.core.model.*;
import com.agentflow.eval.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveEvaluationBudgetTest {
    @Test void explicitPermissionAndCompleteConfigurationRequiredBeforeAnyConnection() {
        assertThrows(IllegalArgumentException.class, () -> LiveEvaluationDriver.connect(Map.of(), false));
        assertThrows(IllegalArgumentException.class, () -> LiveEvaluationDriver.connect(Map.of(), true));
        var p = new Properties(); p.setProperty("agentflow.eval.mode", "live");
        assertThrows(IllegalArgumentException.class, () -> EvaluationEntry.options(p));
    }
    @Test void loopbackProviderUsesReportedUsageCapsOutputAndStopsAtFiveCalls() throws Exception {
        var count = new AtomicInteger(); var maxOutput = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                var request = EvaluationEntry.JSON.readTree(exchange.getRequestBody().readAllBytes());
                maxOutput.set(request.path("max_tokens").asInt()); count.incrementAndGet();
                byte[] response = "{\"id\":\"live-loopback\",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"OK\"}}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var config = Map.of("AGENTFLOW_MODEL_PROVIDER", "openai", "AGENTFLOW_MODEL_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "AGENTFLOW_MODEL_API_KEY", "synthetic-loopback-only", "AGENTFLOW_MODEL_CHAT_MODEL", "fixture");
            var budget = LiveEvaluationDriver.connect(config, true);
            var request = new AgentModelRequest("r", "s", "u", "hello", List.of(ModelMessage.user("hello")), List.of(), 1, 2048);
            for (int i = 0; i < 5; i++) assertEquals(UsageSource.REPORTED, budget.decide(request).usageSource());
            assertEquals(512, maxOutput.get()); assertThrows(IllegalStateException.class, () -> budget.decide(request)); assertEquals(5, count.get());
        } finally { server.stop(0); }
    }
    @Test void elapsedBatchDeadlineStopsBeforeTransportAndNeverFallsBack() {
        var clock = new AtomicLong(); var calls = new AtomicInteger();
        var budget = new LiveEvaluationDriver.Budget(request -> { calls.incrementAndGet(); throw new IllegalStateException("provider failed"); }, clock::get);
        var request = new AgentModelRequest("r", "s", "u", "hello", List.of(ModelMessage.user("hello")), List.of(), 1, 512);
        assertThrows(IllegalStateException.class, () -> budget.decide(request)); assertEquals(1, calls.get());
        clock.set(Duration.ofSeconds(60).toNanos());
        assertThrows(IllegalStateException.class, () -> budget.decide(request)); assertEquals(1, calls.get()); assertFalse(budget.available());
    }
    @Test void liveEntryExecutesOnlyTwoCasesAndPreservesToolUsageThroughActualAdapter() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                count.incrementAndGet();
                var request = EvaluationEntry.JSON.readTree(exchange.getRequestBody().readAllBytes());
                boolean wantsTool = request.path("messages").toString().contains("Call uppercase-text exactly once");
                boolean hasTool = false;
                for (var message : request.path("messages")) hasTool |= message.path("role").asString().equals("tool");
                String message = wantsTool && !hasTool
                        ? "{\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"uppercase-text\",\"arguments\":\"{\\\"text\\\":\\\"hiagent\\\"}\"}}]}"
                        : "{\"content\":\"" + (hasTool ? "HIAGENT" : "OK") + "\"}";
                byte[] response = ("{\"id\":\"live-loopback-" + count.get() + "\",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1},\"choices\":[{\"finish_reason\":\""
                        + (wantsTool && !hasTool ? "tool_calls" : "stop") + "\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var env = Map.of("AGENTFLOW_MODEL_PROVIDER", "openai", "AGENTFLOW_MODEL_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "AGENTFLOW_MODEL_API_KEY", "synthetic-loopback-only", "AGENTFLOW_MODEL_CHAT_MODEL", "fixture");
            var report = EvaluationEntry.runLive(new EvaluationEntry.Options(EvalVariant.baseline(), 1, "live", true), env);
            EvaluationEntry.validateReport(report.document());
            assertTrue(report.passed(), report.document().path("cases").toString()); assertEquals(3, count.get());
            assertEquals(2, report.document().path("summary").path("total").asInt());
            assertEquals("REPORTED", report.document().path("cases").get(1).path("usage").path("status").asString());
            count.set(0);
            var bounded = EvaluationEntry.runLive(new EvaluationEntry.Options(EvalVariant.baseline(), 3, "live", true), env);
            assertFalse(bounded.passed()); assertEquals(5, count.get());
            assertEquals(2, bounded.document().path("summary").path("notRun").asInt());
        } finally { server.stop(0); }
    }
}