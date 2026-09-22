package com.agentflow.llm;

import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.model.EmbeddingCallOptions;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class BoundedEmbeddingClientTest {
    @Test
    void usesIndependentEndpointKeyAndValidatesDimensions() throws Exception {
        var authorization = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "{\"data\":[{\"embedding\":[0.2,0.8,0.1]}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var properties = properties(server);
            properties.model().setApiKey("chat-test-key");
            properties.model().setBaseUrl("http://127.0.0.1:1");
            var client = new OpenAiEmbeddingClient(properties);
            assertThat(client.embed("source", options(Duration.ofSeconds(2)))).containsExactly(0.2, 0.8, 0.1);
            assertThat(authorization.get()).isEqualTo("Bearer embedding-test-key");
            assertThat(body.get()).contains("source", "fixed-test", "dimensions");
        } finally { server.stop(0); }
    }

    @Test
    void rejectsOversizedMalformedZeroAndWrongDimensionResponses() throws Exception {
        for (String response : new String[]{"x".repeat(262_145), "{broken", "{\"data\":[{\"embedding\":[0,0,0]}]}",
                "{\"data\":[{\"embedding\":[1,2]}]}", "{\"data\":[{\"embedding\":[1,\"2\",3]}]}"}) {
            var server = server(response, 0, new AtomicInteger());
            try {
                assertThatThrownBy(() -> new OpenAiEmbeddingClient(properties(server)).embed("text", options(Duration.ofSeconds(2))))
                        .isInstanceOf(ModelClientException.class).hasMessageNotContaining(response);
            } finally { server.stop(0); }
        }
    }

    @Test
    void cancelsBeforeNetworkAndBoundsDelayedResponseBody() throws Exception {
        var requests = new AtomicInteger();
        var server = server("{\"data\":[{\"embedding\":[1,2,3]}]}", 800, requests);
        try {
            var client = new OpenAiEmbeddingClient(properties(server));
            assertThatThrownBy(() -> client.embed("text", new EmbeddingCallOptions(Duration.ofSeconds(1), () -> true)))
                    .isInstanceOf(CancellationException.class);
            assertThat(requests).hasValue(0);
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.embed("text", options(Duration.ofMillis(150))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(700));
        } finally { server.stop(0); }
    }

    private HttpServer server(String text, long delayMillis, AtomicInteger requests) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                byte[] response = text.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try { Thread.sleep(delayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.getResponseBody().write(response);
            } catch (java.io.IOException cancelled) { /* expected when client cancels */ }
        });
        server.start();
        return server;
    }

    @Test
    void cancelsDuringResponseBodyWithoutWaitingForServer() throws Exception {
        var requests = new AtomicInteger();
        var server = server("{\"data\":[{\"embedding\":[1,2,3]}]}", 1200, requests);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var client = new OpenAiEmbeddingClient(properties(server));
            var future = executor.submit(() -> client.embed("text", new EmbeddingCallOptions(Duration.ofSeconds(3), cancelled::get)));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (requests.get() == 0 && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertThat(requests).hasValue(1);
            cancelled.set(true);
            assertThatThrownBy(() -> future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(CancellationException.class);
        } finally {
            cancelled.set(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            server.stop(0);
        }
    }

    private AgentFlowProperties properties(HttpServer server) {
        var properties = new AgentFlowProperties();
        properties.model().setEmbeddingBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.model().setEmbeddingApiKey("embedding-test-key");
        properties.model().setEmbeddingModel("fixed-test");
        properties.model().setEmbeddingDimensions(3);
        return properties;
    }

    private EmbeddingCallOptions options(Duration timeout) {
        return new EmbeddingCallOptions(timeout, CancellationSignal.NONE);
    }
}
