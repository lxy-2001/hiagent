package com.agentflow.mcp;

import com.agentflow.mcp.fixture.LoopbackMcpServer;
import com.agentflow.mcp.fixture.LoopbackMcpServer.Scenario;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkProfileProbeTest {

    private static final Duration BLOCK = Duration.ofSeconds(8);

    @Test
    void jsonInitializeListAndCallStayOnLockedProtocol() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json())) {
            McpAsyncClient client = newClient(server);
            try {
                assertEquals(ProtocolVersions.MCP_2025_11_25, client.initialize().block(BLOCK).protocolVersion());
                ListToolsResult tools = client.listTools(McpSchema.FIRST_PAGE).block(BLOCK);
                assertEquals(1, tools.tools().size());
                assertEquals("project_info", tools.tools().get(0).name());
                CallToolResult result = client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK);
                assertEquals("ok", ((TextContent) result.content().get(0)).text());
                assertEquals(1, server.initializeCount());
                assertEquals(1, server.listCount());
                assertEquals(1, server.callCount());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void sseResponseDecodesAndDoesNotCountGetAsToolCall() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().sse().session())) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                CallToolResult result = client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK);
                assertEquals("ok", ((TextContent) result.content().get(0)).text());
                assertEquals(1, server.callCount());
                assertNotNull(server.sessionId());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void sessionGetMayReturn405WithoutFailingTheClient() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().session())) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                waitUntil(() -> server.getCount() >= 1, Duration.ofSeconds(2));
                ListToolsResult tools = client.listTools(McpSchema.FIRST_PAGE).block(BLOCK);
                assertEquals(1, tools.tools().size());
                assertEquals(1, server.listCount());
                assertTrue(server.getCount() >= 1);
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void listChangedNotificationDoesNotTriggerAutomaticListTools() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(
                Scenario.json().session().allowGetSse().emitListChangedOnGet()
                        .extraSseNotifications(8).holdGet(Duration.ofSeconds(3)))) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                waitUntil(() -> server.getCount() >= 1, Duration.ofSeconds(2));
                sleep(Duration.ofMillis(400));
                assertEquals(0, server.listCount());
                client.listTools(McpSchema.FIRST_PAGE).block(BLOCK);
                assertEquals(1, server.listCount());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void wrongNegotiatedVersionDoesNotListOrCall() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().protocolVersion("2025-03-26"))) {
            McpAsyncClient client = newClient(server);
            try {
                assertThrows(RuntimeException.class, () -> client.initialize().block(BLOCK));
                assertEquals(0, server.listCount());
                assertEquals(0, server.callCount());
                assertThrows(RuntimeException.class, () -> client.listTools(McpSchema.FIRST_PAGE).block(BLOCK));
                assertThrows(RuntimeException.class,
                        () -> client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK));
                assertEquals(0, server.listCount());
                assertEquals(0, server.callCount());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void unauthorizedDoesNotRetry() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().requireBearer())) {
            McpAsyncClient client = newClient(server);
            try {
                assertThrows(RuntimeException.class, () -> client.initialize().block(BLOCK));
                assertEquals(1, server.postCount());
                sleep(Duration.ofMillis(400));
                assertEquals(1, server.postCount());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void missingEndpointIs404AndDoesNotRetry() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().notFound())) {
            McpAsyncClient client = newClient(server);
            try {
                assertThrows(RuntimeException.class, () -> client.initialize().block(BLOCK));
                assertEquals(1, server.postCount());
                sleep(Duration.ofMillis(400));
                assertEquals(1, server.postCount());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void droppedCallStreamIsNotResent() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().dropAfterCallHeaders())) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                int initializes = server.initializeCount();
                assertThrows(RuntimeException.class,
                        () -> client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK));
                assertEquals(1, server.callCount());
                assertTrue(server.awaitQuiet(Duration.ofSeconds(1), server.postCount(), initializes, 1));
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void droppedPostSseCallStreamIsNotResent() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().dropSseAfterCallHeaders())) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                int initializes = server.initializeCount();
                assertThrows(RuntimeException.class,
                        () -> client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK));
                assertEquals(1, server.callCount());
                assertTrue(server.awaitQuiet(Duration.ofSeconds(1), server.postCount(), initializes, 1));
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void callUnauthorizedDoesNotReinitializeOrReplay() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().unauthorizedOnCall())) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                int initializes = server.initializeCount();
                int postsAfterInit = server.postCount();
                assertTrue(initializes >= 1);
                assertThrows(RuntimeException.class,
                        () -> client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK));
                assertEquals(1, server.callCount());
                assertEquals(initializes, server.initializeCount());
                assertEquals(postsAfterInit + 1, server.postCount());
                assertTrue(server.awaitQuiet(Duration.ofSeconds(1), postsAfterInit + 1, initializes, 1));
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void callSessionGoneDoesNotReinitializeOrReplay() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().session().sessionGoneOnCall())) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                int initializes = server.initializeCount();
                int postsAfterInit = server.postCount();
                assertThrows(RuntimeException.class,
                        () -> client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK));
                assertEquals(1, server.callCount());
                assertEquals(initializes, server.initializeCount());
                assertEquals(postsAfterInit + 1, server.postCount());
                assertTrue(server.awaitQuiet(Duration.ofSeconds(1), postsAfterInit + 1, initializes, 1));
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void cancelAndCloseDoNotResendTheCall() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().callDelay(Duration.ofSeconds(3)))) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                int initializes = server.initializeCount();
                AtomicReference<Throwable> error = new AtomicReference<>();
                Disposable disposable = client.callTool(new CallToolRequest("project_info", Map.of()))
                        .subscribe(result -> {
                        }, error::set);
                assertTrue(server.awaitCall(Duration.ofSeconds(2)));
                assertEquals(1, server.callCount());
                disposable.dispose();
                client.closeGracefully().block(BLOCK);
                assertTrue(server.awaitQuiet(Duration.ofSeconds(1), server.postCount(), initializes, 1));
                assertEquals(1, server.callCount());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void singleInboundMessageLargerThan256KiBIsRejected() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().oversizedBodyBytes(300 * 1024))) {
            McpAsyncClient client = newClient(server);
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> assertThrows(RuntimeException.class, () -> client.initialize().block(BLOCK)));
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void jsonWithoutContentLengthStillCompletesWithinBound() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().omitContentLength())) {
            McpAsyncClient client = newClient(server);
            try {
                assertEquals(ProtocolVersions.MCP_2025_11_25, client.initialize().block(BLOCK).protocolVersion());
                assertEquals(1, client.listTools(McpSchema.FIRST_PAGE).block(BLOCK).tools().size());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void oversizedChunkedJsonWithoutContentLengthIsRejected() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().oversizedChunkedJson(300 * 1024))) {
            McpAsyncClient client = newClient(server);
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> assertThrows(RuntimeException.class, () -> client.initialize().block(BLOCK)));
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void closeReleasesExecutorAndExchanges() {
        LoopbackMcpServer server = LoopbackMcpServer.start(
                Scenario.json().session().allowGetSse().holdGet(Duration.ofSeconds(3)));
        McpAsyncClient client = newClient(server);
        try {
            client.initialize().block(BLOCK);
            waitUntil(() -> server.getCount() >= 1, Duration.ofSeconds(2));
        }
        finally {
            client.close();
            server.close();
        }
        assertTrue(server.executorTerminated());
        assertEquals(0, server.retainedExchangeCount());
    }

    @Test
    void unboundedSseEventIsAbortedInsteadOfBufferedForever() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json().sse().infiniteEvent())) {
            McpAsyncClient client = newClient(server);
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(6),
                        () -> assertThrows(RuntimeException.class, () -> client.initialize().block(BLOCK)));
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void unusedNotificationBurstIsRateLimitedAndClosesTransport() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(
                Scenario.json().sse().session().allowGetSse().extraSseNotifications(40).holdGet(Duration.ofSeconds(2)))) {
            McpAsyncClient client = newClient(server);
            try {
                try {
                    client.initialize().block(BLOCK);
                }
                catch (RuntimeException ignored) {
                    // 40 unused notifications may close the transport on the initialize SSE stream.
                }
                sleep(Duration.ofMillis(400));
                assertThrows(RuntimeException.class,
                        () -> client.listTools(McpSchema.FIRST_PAGE).block(BLOCK));
                assertEquals(0, server.listCount());
                assertEquals(0, server.callCount());
            }
            finally {
                client.close();
            }
        }
    }

    @Test
    void eachLogicalCallIsSubscribedOnce() {
        try (LoopbackMcpServer server = LoopbackMcpServer.start(Scenario.json())) {
            McpAsyncClient client = newClient(server);
            try {
                client.initialize().block(BLOCK);
                client.callTool(new CallToolRequest("project_info", Map.of())).block(BLOCK);
                assertEquals(1, server.callCount());
                sleep(Duration.ofMillis(300));
                assertEquals(1, server.callCount());
            }
            finally {
                client.close();
            }
        }
    }

    private static McpAsyncClient newClient(LoopbackMcpServer server) {
        return McpClient.async(ProfiledMcpTransport.create(server.endpoint()))
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .clientInfo(new Implementation("hiagent-probe", "0.1.0"))
                .capabilities(ClientCapabilities.builder().build())
                .enableCallToolSchemaCaching(false)
                .build();
    }

    private static void waitUntil(Check check, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            sleep(Duration.ofMillis(50));
        }
        assertTrue(check.ok(), "condition not met before timeout");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
