package com.agentflow.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfiledMcpTransportBudgetTest {

    private static final Duration BLOCK = Duration.ofSeconds(2);

    @Test
    void droppedNotificationsAtRateLimitAreCountedAndNotForwarded() {
        Harness harness = harness();
        McpSchema.JSONRPCNotification dropped = listChanged();
        for (int i = 0; i < ProfiledMcpTransport.MAX_NOTIFICATIONS_PER_SECOND; i++) {
            emit(harness, dropped);
        }
        assertEquals(0, harness.forwarded.get());
        assertFalse(harness.inner.closed());
        harness.transport.sendMessage(clientRequest("tools/list", 1)).block(BLOCK);
        assertFalse(harness.inner.closed());
    }

    @Test
    void droppedNotificationsOverRateLimitCloseAndRejectLaterUse() {
        Harness harness = harness();
        McpSchema.JSONRPCNotification dropped = logging();
        for (int i = 0; i < ProfiledMcpTransport.MAX_NOTIFICATIONS_PER_SECOND; i++) {
            emit(harness, dropped);
        }
        IllegalStateException exceeded = assertThrows(IllegalStateException.class, () -> emit(harness, dropped));
        assertTrue(exceeded.getMessage().contains("notification rate exceeded"));
        assertTrue(harness.inner.closed());
        assertEquals(0, harness.forwarded.get());
        IllegalStateException send = assertThrows(IllegalStateException.class,
                () -> harness.transport.sendMessage(clientRequest("tools/list", 1)).block(BLOCK));
        assertTrue(send.getMessage().contains("already closed"));
        IllegalStateException inbound = assertThrows(IllegalStateException.class, () -> emit(harness, dropped));
        assertTrue(inbound.getMessage().contains("already closed"));
    }

    @Test
    void unusedListChangedBelowRateLimitIsNotForwarded() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/list", 1)).block(BLOCK);
        emit(harness, listChanged());
        assertEquals(0, harness.forwarded.get());
        assertFalse(harness.inner.closed());
    }

    @Test
    void droppedNotificationsCountTowardNonFinalOperationBudget() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        for (int i = 0; i < 16; i++) {
            emit(harness, serverRequest(i + 1));
        }
        McpSchema.JSONRPCNotification dropped = logging();
        for (int i = 0; i < 16; i++) {
            emit(harness, dropped);
        }
        assertEquals(16, harness.forwarded.get());
        assertFalse(harness.inner.closed());
        IllegalStateException exceeded = assertThrows(IllegalStateException.class, () -> emit(harness, dropped));
        assertTrue(exceeded.getMessage().contains("non-final message count exceeded"));
        assertTrue(harness.inner.closed());
    }

    @Test
    void outboundResponseDoesNotResetOperationBudget() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        for (int i = 0; i < ProfiledMcpTransport.MAX_NON_FINAL_MESSAGES; i++) {
            emit(harness, serverRequest(i + 1));
        }
        assertFalse(harness.inner.closed());
        harness.transport.sendMessage(clientResponse(99)).block(BLOCK);
        IllegalStateException exceeded = assertThrows(IllegalStateException.class,
                () -> emit(harness, serverRequest(100)));
        assertTrue(exceeded.getMessage().contains("non-final message count exceeded"));
        assertTrue(harness.inner.closed());
    }

    @Test
    void outboundNotificationDoesNotResetOperationBudget() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        for (int i = 0; i < ProfiledMcpTransport.MAX_NON_FINAL_MESSAGES; i++) {
            emit(harness, serverRequest(i + 1));
        }
        harness.transport.sendMessage(initialized()).block(BLOCK);
        IllegalStateException exceeded = assertThrows(IllegalStateException.class,
                () -> emit(harness, serverRequest(100)));
        assertTrue(exceeded.getMessage().contains("non-final message count exceeded"));
        assertTrue(harness.inner.closed());
    }

    @Test
    void droppedNotificationsCountTowardDecodedByteBudget() throws IOException {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        McpSchema.JSONRPCNotification dropped = loggingWithPayload("x".repeat(80_000));
        int size = harness.mapper.writeValueAsBytes(dropped).length;
        int sent = 0;
        int used = 0;
        while (used + size <= ProfiledMcpTransport.MAX_DECODED_BYTES) {
            emit(harness, dropped);
            used += size;
            sent++;
            assertTrue(sent < ProfiledMcpTransport.MAX_NON_FINAL_MESSAGES);
        }
        IllegalStateException exceeded = assertThrows(IllegalStateException.class, () -> emit(harness, dropped));
        assertTrue(exceeded.getMessage().contains("decoded message budget exceeded"));
        assertTrue(harness.inner.closed());
        assertEquals(0, harness.forwarded.get());
    }

    @Test
    void inboundResponseThenNextClientRequestResetsOperationBudget() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        for (int i = 0; i < ProfiledMcpTransport.MAX_NON_FINAL_MESSAGES; i++) {
            emit(harness, serverRequest(i + 1));
        }
        emit(harness, clientBoundResponse(1));
        assertFalse(harness.inner.closed());
        harness.transport.sendMessage(clientRequest("tools/list", 2)).block(BLOCK);
        for (int i = 0; i < ProfiledMcpTransport.MAX_NON_FINAL_MESSAGES; i++) {
            emit(harness, serverRequest(200 + i));
        }
        assertFalse(harness.inner.closed());
    }

    @Test
    void mismatchedResponseIdClosesAndDoesNotReleaseBudget() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        for (int i = 0; i < 8; i++) {
            emit(harness, serverRequest(i + 1));
        }
        IllegalStateException mismatched = assertThrows(IllegalStateException.class,
                () -> emit(harness, clientBoundResponse(99)));
        assertTrue(mismatched.getMessage().contains("unknown or mismatched response id"));
        assertTrue(harness.inner.closed());
        IllegalStateException send = assertThrows(IllegalStateException.class,
                () -> harness.transport.sendMessage(clientRequest("tools/list", 2)).block(BLOCK));
        assertTrue(send.getMessage().contains("already closed"));
    }

    @Test
    void unknownResponseIdWithNoActiveOperationCloses() {
        Harness harness = harness();
        IllegalStateException unknown = assertThrows(IllegalStateException.class,
                () -> emit(harness, clientBoundResponse(1)));
        assertTrue(unknown.getMessage().contains("unknown or mismatched response id"));
        assertTrue(harness.inner.closed());
    }

    @Test
    void lateResponseAfterCompletedOperationIsRejected() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        emit(harness, clientBoundResponse(1));
        assertFalse(harness.inner.closed());
        IllegalStateException late = assertThrows(IllegalStateException.class,
                () -> emit(harness, clientBoundResponse(1)));
        assertTrue(late.getMessage().contains("unknown or mismatched response id"));
        assertTrue(harness.inner.closed());
    }

    @Test
    void overlappingClientRequestIsRejectedWithoutResettingBudget() {
        Harness harness = harness();
        harness.transport.sendMessage(clientRequest("tools/call", 1)).block(BLOCK);
        for (int i = 0; i < ProfiledMcpTransport.MAX_NON_FINAL_MESSAGES; i++) {
            emit(harness, serverRequest(i + 1));
        }
        IllegalStateException overlapping = assertThrows(IllegalStateException.class,
                () -> harness.transport.sendMessage(clientRequest("tools/list", 2)).block(BLOCK));
        assertTrue(overlapping.getMessage().contains("overlapping client request"));
        assertTrue(harness.inner.closed());
        IllegalStateException inbound = assertThrows(IllegalStateException.class,
                () -> emit(harness, serverRequest(100)));
        assertTrue(inbound.getMessage().contains("already closed"));
    }

    private static void emit(Harness harness, McpSchema.JSONRPCMessage message) {
        harness.inner.emit(message).block(BLOCK);
    }

    private static Harness harness() {
        ControllableTransport inner = new ControllableTransport();
        McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        ProfiledMcpTransport transport = new ProfiledMcpTransport(inner, mapper);
        AtomicInteger forwarded = new AtomicInteger();
        transport.connect(incoming -> incoming.doOnNext(message -> forwarded.incrementAndGet()))
                .block(Duration.ofSeconds(1));
        return new Harness(inner, transport, mapper, forwarded);
    }

    private static McpSchema.JSONRPCRequest clientRequest(String method, int id) {
        return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, id, Map.of());
    }

    private static McpSchema.JSONRPCRequest serverRequest(int id) {
        return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", id, Map.of());
    }

    private static McpSchema.JSONRPCResponse clientResponse(int id) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, id, Map.of("ok", true), null);
    }

    private static McpSchema.JSONRPCResponse clientBoundResponse(int id) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, id, Map.of("content", List.of()), null);
    }

    private static McpSchema.JSONRPCNotification listChanged() {
        return new McpSchema.JSONRPCNotification(
                McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, Map.of());
    }

    private static McpSchema.JSONRPCNotification logging() {
        return new McpSchema.JSONRPCNotification(
                McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_MESSAGE, Map.of());
    }

    private static McpSchema.JSONRPCNotification loggingWithPayload(String payload) {
        return new McpSchema.JSONRPCNotification(
                McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_MESSAGE, Map.of("data", payload));
    }

    private static McpSchema.JSONRPCNotification initialized() {
        return new McpSchema.JSONRPCNotification(
                McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_INITIALIZED, Map.of());
    }

    private record Harness(
            ControllableTransport inner,
            ProfiledMcpTransport transport,
            McpJsonMapper mapper,
            AtomicInteger forwarded) {
    }

    private static final class ControllableTransport implements McpClientTransport {

        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inboundHandler;

        Mono<McpSchema.JSONRPCMessage> emit(McpSchema.JSONRPCMessage message) {
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = inboundHandler;
            if (handler == null) {
                return Mono.error(new IllegalStateException("test transport not connected"));
            }
            return handler.apply(Mono.just(message));
        }

        boolean closed() {
            return closed.get();
        }

        @Override
        public List<String> protocolVersions() {
            return List.of(ProtocolVersions.MCP_2025_11_25);
        }

        @Override
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            this.inboundHandler = handler;
            return Mono.empty();
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> handler) {
        }

        @Override
        public Mono<Void> closeGracefully() {
            closed.set(true);
            return Mono.empty();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            if (closed.get()) {
                return Mono.error(new IllegalStateException("delegate already closed"));
            }
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            throw new UnsupportedOperationException();
        }
    }
}
