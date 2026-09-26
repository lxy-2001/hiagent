package com.agentflow.demo.evaluation;

import com.agentflow.core.model.*;
import com.agentflow.llm.ProviderDecisionMapper;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;

/** Real loopback HTTP response parsed by the product mapper; deliberately omits usage. */
final class MissingUsageModel implements AgentModelClient, AutoCloseable {
    private final HttpServer server;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    MissingUsageModel() throws java.io.IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readNBytes(65536);
                byte[] response = "{\"id\":\"loopback\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"OK\"}}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
    }
    public ModelDecision decide(AgentModelRequest request) {
        var json = new ObjectMapper();
        var body = ProviderDecisionMapper.requestBody(request, "offline", json);
        var http = HttpRequest.newBuilder(OfflineConnections.requireLoopback(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions")))
                .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        try {
            var response = client.send(http, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IllegalStateException("LOOPBACK_MODEL_FAILED");
            return ProviderDecisionMapper.decision(json.readTree(response.body()), json);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("LOOPBACK_INTERRUPTED"); }
        catch (java.io.IOException e) { throw new IllegalStateException("LOOPBACK_MODEL_FAILED"); }
    }
    public void close() { server.stop(0); }
}
