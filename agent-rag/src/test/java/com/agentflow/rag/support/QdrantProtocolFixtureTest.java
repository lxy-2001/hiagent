package com.agentflow.rag.support;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantProtocolFixtureTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void servesMalformedAndOversizedResponsesAndRecordsRequests() throws Exception {
        try (var fixture = new QdrantProtocolFixture()) {
            assertThat(fixture.uri().getHost()).isEqualTo("127.0.0.1");
            fixture.enqueue(200, "{invalid");
            fixture.enqueue(503, "x".repeat(131_073));
            var request = HttpRequest.newBuilder(fixture.uri().resolve("/collections/test/points?wait=true"))
                    .timeout(Duration.ofSeconds(3)).PUT(HttpRequest.BodyPublishers.ofString("{\"points\":[]}")).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("{invalid");
            var oversized = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(oversized.statusCode()).isEqualTo(503);
            assertThat(oversized.body()).hasSize(131_073);
            assertThat(fixture.requests()).hasSize(2);
            assertThat(fixture.requests().get(0)).isEqualTo(new QdrantProtocolFixture.Request(
                    "PUT", "/collections/test/points?wait=true", "{\"points\":[]}"));
        }
    }

    @Test
    void delaysTheBodyAfterHeadersAndReleasesItsPort() throws Exception {
        int port;
        try (var fixture = new QdrantProtocolFixture()) {
            port = fixture.uri().getPort();
            fixture.enqueue(200, "{}", Duration.ofMillis(250));
            long started = System.nanoTime();
            var request = HttpRequest.newBuilder(fixture.uri()).timeout(Duration.ofSeconds(3)).build();
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get(3, TimeUnit.SECONDS);
            assertThat(response.body()).isEqualTo("{}");
            assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(Duration.ofMillis(200).toNanos());
        }
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            assertThat(socket.isBound()).isTrue();
        }
    }

    @Test
    void unscriptedRequestsFailInsteadOfPretendingSuccess() throws Exception {
        try (var fixture = new QdrantProtocolFixture()) {
            var request = HttpRequest.newBuilder(fixture.uri()).timeout(Duration.ofSeconds(3)).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(500);
        }
    }
}
