package com.agentflow.rag.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Scripted loopback transport fixture; never registered as a production vector index. */
public final class QdrantProtocolFixture implements AutoCloseable {
    public record Request(String method, String path, String body) { }

    private record Response(int status, String body, Duration bodyDelay) { }

    private final HttpServer server;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();

    public QdrantProtocolFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                var response = responses.poll();
                if (response == null) {
                    response = new Response(500, "{\"error\":\"unscripted request\"}", Duration.ZERO);
                }
                var bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                try {
                    TimeUnit.NANOSECONDS.sleep(response.bodyDelay().toNanos());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                exchange.getResponseBody().write(bytes);
            } catch (IOException disconnected) {
                // Client cancellation deliberately closes the response stream.
            }
        });
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void enqueue(int status, String body) {
        enqueue(status, body, Duration.ZERO);
    }

    public void enqueue(int status, String body, Duration bodyDelay) {
        if (status < 200 || status > 599 || body == null || body.isEmpty()
                || bodyDelay == null || bodyDelay.isNegative()) {
            throw new IllegalArgumentException("Invalid scripted response");
        }
        responses.add(new Response(status, body, bodyDelay));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fixture executor did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing fixture", interrupted);
        }
    }
}
