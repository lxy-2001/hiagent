package com.agentflow.mcp;

import com.agentflow.core.runtime.*;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.mcp.fixture.LoopbackMcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SdkMcpClientOperationsTest {
    static ToolExecutionControl control() {return new ToolExecutionControl(CancellationSignal.NONE,TimeSource.system(),Duration.ofSeconds(10));}
    static SdkMcpClientOperations client(LoopbackMcpServer server) {
        return new SdkMcpClientOperations(new McpProperties.Server("demo",server.endpoint(),"",Set.of("project_info")),Duration.ofSeconds(2));
    }
    @ParameterizedTest
    @CsvSource({
            "2025-11-25,false,false", "2025-11-25,false,true",
            "2025-11-25,true,false", "2025-11-25,true,true",
            "2025-06-18,false,false", "2025-06-18,false,true",
            "2025-06-18,true,false", "2025-06-18,true,true"
    })
    void negotiatedVersionControlsCompleteExchange(String version, boolean sse, boolean session) {
        var scenario = LoopbackMcpServer.Scenario.json().protocolVersion(version);
        if (sse) scenario.sse();
        if (session) scenario.session();
        try (var server = LoopbackMcpServer.start(scenario); var client = client(server)) {
            client.initialize(control());
            assertEquals(1, client.listTools(null, control()).tools().size());
            assertTrue(client.call("project_info", new ToolArguments(Map.of()), control()).containsKey("content"));
            assertEquals(1, server.initializeCount());
            assertEquals(1, server.initializedCount());
            assertEquals(1, server.listCount());
            assertEquals(1, server.callCount());
            assertEquals("2025-11-25", server.requests().get(0).proposedVersion());
            for (var request : server.requests()) {
                if ("initialize".equals(request.method())) continue;
                assertEquals(version, request.protocol(), request.method());
                assertEquals(session ? server.sessionId() : null, request.session(), request.method());
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2025-03-26", "2099-01-01", "", "untrusted\nversion"})
    void invalidNegotiationClosesClientWithoutFurtherRequests(String version) {
        try (var server = LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().protocolVersion(version));
             var client = client(server)) {
            assertRejectedAndClosed(client, server);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2025-06-18"})
    void missingToolsCapabilityClosesClient(String version) {
        try (var server = LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().protocolVersion(version).withoutTools());
             var client = client(server)) {
            assertRejectedAndClosed(client, server);
        }
    }

    private static void assertRejectedAndClosed(SdkMcpClientOperations client, LoopbackMcpServer server) {
        var logger = (Logger) LoggerFactory.getLogger(SdkMcpClientOperations.class);
        var events = new ListAppender<ILoggingEvent>();
        var previousLevel = logger.getLevel();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.WARN);
        McpOperationException failure;
        try {
            failure = assertThrows(McpOperationException.class, () -> client.initialize(control()));
            assertEquals(1, events.list.size());
            String diagnostic = events.list.get(0).getFormattedMessage();
            assertTrue(diagnostic.contains("supported=[2025-06-18, 2025-11-25]"));
            assertTrue(diagnostic.matches("MCP initialization rejected: supported=\\[2025-06-18, 2025-11-25\\], server=(missing|invalid|[0-9]{4}-[0-9]{2}-[0-9]{2})"));
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            events.stop();
        }
        assertEquals("MCP_PROTOCOL_ERROR", failure.code());
        assertEquals("MCP_PROTOCOL_ERROR", failure.getMessage());
        assertThrows(McpOperationException.class, () -> client.listTools(null, control()));
        assertThrows(McpOperationException.class,
                () -> client.call("project_info", new ToolArguments(Map.of()), control()));
        assertEquals(1, server.initializeCount());
        assertEquals(0, server.listCount());
        assertEquals(0, server.callCount());
    }

    @Test void realSdkInitializesListsAndCallsOnce() {
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json());var client=client(server)) {
            client.initialize(control());assertEquals(1,client.listTools(null,control()).tools().size());
            assertTrue(client.call("project_info",new ToolArguments(Map.of()),control()).containsKey("content"));
            assertEquals(1,server.callCount());assertEquals(1,server.listCount());
        }
    }
    @Test void discoveryRetainsConstraintsNotRepresentableBySdkSchemaRecord() {
        String page="{\"tools\":[{\"name\":\"project_info\",\"inputSchema\":{\"type\":\"object\",\"additionalProperties\":false,\"allOf\":[]}}]}";
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().listResult(page));var client=client(server)) {
            client.initialize(control());var tools=client.listTools(null,control()).tools();
            assertEquals(1,tools.size());
            assertTrue(((Map<?,?>)tools.get(0).get("inputSchema")).containsKey("allOf"));
            assertEquals(1,server.listCount());assertEquals(0,server.callCount());
        }
    }
    @Test void unavailableCallDoesNotRetryOrExposeTransportText() {
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().unauthorizedOnCall());var client=client(server)) {
            client.initialize(control());
            var ex=assertThrows(McpOperationException.class,()->client.call("project_info",new ToolArguments(Map.of()),control()));
            assertEquals("MCP_UNAVAILABLE",ex.code());assertEquals(1,server.callCount());assertEquals(1,server.initializeCount());
        }
    }

    @Test void wrongResponseIdIsProtocolFailureAndCannotBeReused() {
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().wrongCallId());var client=client(server)) {
            client.initialize(control());
            var failure=assertThrows(McpOperationException.class,()->client.call("project_info",new ToolArguments(Map.of()),control()));
            assertEquals("MCP_PROTOCOL_ERROR",failure.code());assertEquals(1,server.callCount());
        }
    }
    @Test void operationDeadlineStopsDelayedResponseWithoutRetry() {
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().callDelay(Duration.ofSeconds(2)));var client=client(server)) {
            client.initialize(control());
            var limited=new ToolExecutionControl(CancellationSignal.NONE,TimeSource.system(),Duration.ofMillis(150));
            var failure=assertThrows(McpOperationException.class,()->client.call("project_info",new ToolArguments(Map.of()),limited));
            assertEquals("MCP_TIMEOUT",failure.code());assertEquals(1,server.callCount());
        }
    }
    @Test void cancellationAfterRemoteReceiveNeverResubscribes() throws Exception {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().callDelay(Duration.ofSeconds(2)));var client=client(server)) {
            client.initialize(control());
            var active=new ToolExecutionControl(cancelled::get,TimeSource.system(),Duration.ofSeconds(5));
            var result=executor.submit(()->client.call("project_info",new ToolArguments(Map.of()),active));
            assertTrue(server.awaitCall(Duration.ofSeconds(2)));cancelled.set(true);
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->result.get(1,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED",((McpOperationException)failure.getCause()).code());assertEquals(1,server.callCount());
        } finally {executor.shutdownNow();}
    }

    @Test void redirectsNeverMoveCredentialsOrRequestsToAnotherTarget() {
        try(var target=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json());
            var source=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().redirectTo(target.endpoint()));
            var client=client(source)) {
            assertThrows(McpOperationException.class,()->client.initialize(control()));
            assertEquals(0,target.postCount());assertEquals(1,source.postCount());
        }
    }
    @Test void bearerBelongsOnlyToItsConfiguredClient() {
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().requireBearer());
            var authenticated=new SdkMcpClientOperations(new McpProperties.Server("demo",server.endpoint(),LoopbackMcpServer.BEARER,Set.of("project_info")),Duration.ofSeconds(2));
            var anonymous=client(server)) {
            authenticated.initialize(control());
            assertThrows(McpOperationException.class,()->anonymous.initialize(control()));
            assertEquals(1,server.initializeCount());
        }
    }

    @Test void malformedJsonIsAProtocolFailure() {
        try(var server=LoopbackMcpServer.start(LoopbackMcpServer.Scenario.json().callResult("{broken"));var client=client(server)) {
            client.initialize(control());
            var failure=assertThrows(McpOperationException.class,()->client.call("project_info",new ToolArguments(Map.of()),control()));
            assertEquals("MCP_PROTOCOL_ERROR",failure.code());assertEquals(1,server.callCount());
        }
    }
}
