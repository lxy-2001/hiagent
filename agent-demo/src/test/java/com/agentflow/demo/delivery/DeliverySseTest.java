package com.agentflow.demo.delivery;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class DeliverySseTest {
    @Test void lateSubscriberHasOneTerminalAndFinalCursorReturns204ThenPortCloses() throws Exception {
        int port;
        try (var fixture = DeliveryDemoLauncher.start("happy-path", 0)) {
            port = fixture.port();
            String id = fixture.create("remember", null, false).path("taskId").asString();
            fixture.awaitTerminal(id);
            var response = fixture.events(id, null);
            assertThat(response.statusCode()).isEqualTo(200);
            var json = new ObjectMapper();
            var events = response.body().lines().filter(line -> line.startsWith("data:")).map(line -> json.readTree(line.substring(5).strip())).toList();
            assertThat(events).isNotEmpty();
            long expected = 1;
            for (var event : events) {
                assertThat(event.path("runId").asString()).isEqualTo(id);
                assertThat(Long.parseLong(event.path("eventId").asString())).isEqualTo(expected++);
            }
            assertThat(events.stream().filter(e -> e.path("type").asString().equals("RUN_TERMINATED")).count()).isEqualTo(1);
            assertThat(fixture.events(id, Long.toString(expected - 1)).statusCode()).isEqualTo(204);
        }
        try (var socket = new Socket()) {
            assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", port), 500)).isInstanceOf(java.io.IOException.class);
        }
    }
}
