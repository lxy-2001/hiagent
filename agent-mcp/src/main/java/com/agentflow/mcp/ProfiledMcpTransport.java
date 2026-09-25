package com.agentflow.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Streamable HTTP transport with the Feature 006 profile: MCP 2025-11-25 only,
 * 256KiB inbound messages, no resumability or auth retry, and unused notifications
 * dropped before SDK handlers (so list_changed cannot start unbounded listTools).
 */
public final class ProfiledMcpTransport implements McpClientTransport {

    public static final int MAX_INBOUND_MESSAGE_BYTES = 256 * 1024;
    public static final int MAX_NON_FINAL_MESSAGES = 32;
    public static final int MAX_DECODED_BYTES = 1024 * 1024;
    public static final int MAX_NOTIFICATIONS_PER_SECOND = 32;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Set<String> DROPPED_NOTIFICATIONS = Set.of(
            McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
            McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,
            McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
            McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED,
            McpSchema.METHOD_NOTIFICATION_PROGRESS,
            McpSchema.METHOD_NOTIFICATION_MESSAGE);

    private static final McpHttpClientAuthorizationErrorHandler NO_AUTH_RETRY =
            new McpHttpClientAuthorizationErrorHandler() {
                @Override
                public Publisher<Boolean> handle(HttpResponse.ResponseInfo responseInfo, McpTransportContext context) {
                    return Mono.just(false);
                }

                @Override
                public int maxRetries() {
                    return 0;
                }
            };

    private final McpClientTransport delegate;
    private final McpJsonMapper jsonMapper;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger nonFinalMessages = new AtomicInteger();
    private final AtomicLong decodedBytes = new AtomicLong();
    private final AtomicInteger notificationWindowCount = new AtomicInteger();
    private final AtomicLong notificationWindowStart = new AtomicLong(System.nanoTime());
    private final AtomicBoolean operationActive = new AtomicBoolean();
    private final AtomicReference<Object> activeRequestId = new AtomicReference<>();

    ProfiledMcpTransport(McpClientTransport delegate, McpJsonMapper jsonMapper) {
        this.delegate = delegate;
        this.jsonMapper = jsonMapper;
    }

    public static McpClientTransport create(URI endpoint) {
        McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        URI origin = URI.create(endpoint.getScheme() + "://" + authority(endpoint));
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(origin.toString())
                .endpoint(path(endpoint))
                .jsonMapper(mapper)
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .connectTimeout(CONNECT_TIMEOUT)
                .maxResponseSize(MAX_INBOUND_MESSAGE_BYTES)
                .supportedProtocolVersions(List.of(ProtocolVersions.MCP_2025_11_25))
                .authorizationErrorHandler(NO_AUTH_RETRY)
                .customizeClient(client -> client
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(CONNECT_TIMEOUT))
                .build();
        return new ProfiledMcpTransport(transport, mapper);
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(ProtocolVersions.MCP_2025_11_25);
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(incoming -> incoming.flatMap(message -> {
            try {
                noteInbound(message);
            }
            catch (RuntimeException ex) {
                closeQuietly();
                return Mono.error(ex);
            }
            if (unusedNotification(message)) {
                return Mono.empty();
            }
            return handler.apply(Mono.just(message));
        }));
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        delegate.setExceptionHandler(handler);
    }

    @Override
    public Mono<Void> closeGracefully() {
        closed.set(true);
        endOperation();
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        closed.set(true);
        endOperation();
        delegate.close();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        if (closed.get()) {
            return Mono.error(new IllegalStateException("MCP transport already closed"));
        }
        if (message instanceof McpSchema.JSONRPCRequest request) {
            if (!activeRequestId.compareAndSet(null, request.id())) {
                closeQuietly();
                return Mono.error(new IllegalStateException("MCP overlapping client request"));
            }
            beginOperation();
        }
        return delegate.sendMessage(message);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return delegate.unmarshalFrom(data, typeRef);
    }

    private void noteInbound(McpSchema.JSONRPCMessage message) {
        if (closed.get()) {
            throw new IllegalStateException("MCP transport already closed");
        }
        if (message instanceof McpSchema.JSONRPCNotification) {
            noteNotificationRate();
        }
        if (message instanceof McpSchema.JSONRPCResponse response) {
            Object expected = activeRequestId.get();
            if (expected == null || !Objects.equals(expected, response.id())) {
                throw new IllegalStateException("MCP unknown or mismatched response id");
            }
        }
        if (!operationActive.get()) {
            return;
        }
        int size = encodedSize(message);
        if (decodedBytes.addAndGet(size) > MAX_DECODED_BYTES) {
            throw new IllegalStateException("MCP decoded message budget exceeded");
        }
        if (message instanceof McpSchema.JSONRPCNotification || message instanceof McpSchema.JSONRPCRequest) {
            if (nonFinalMessages.incrementAndGet() > MAX_NON_FINAL_MESSAGES) {
                throw new IllegalStateException("MCP non-final message count exceeded");
            }
        }
        if (message instanceof McpSchema.JSONRPCResponse) {
            endOperation();
        }
    }

    private void beginOperation() {
        nonFinalMessages.set(0);
        decodedBytes.set(0);
        operationActive.set(true);
    }

    private void endOperation() {
        operationActive.set(false);
        activeRequestId.set(null);
        nonFinalMessages.set(0);
        decodedBytes.set(0);
    }

    private void noteNotificationRate() {
        long now = System.nanoTime();
        long start = notificationWindowStart.get();
        if (now - start >= 1_000_000_000L && notificationWindowStart.compareAndSet(start, now)) {
            notificationWindowCount.set(0);
        }
        if (notificationWindowCount.incrementAndGet() > MAX_NOTIFICATIONS_PER_SECOND) {
            throw new IllegalStateException("MCP notification rate exceeded");
        }
    }

    private int encodedSize(McpSchema.JSONRPCMessage message) {
        try {
            return jsonMapper.writeValueAsBytes(message).length;
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to size MCP message", ex);
        }
    }

    private void closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            endOperation();
            delegate.close();
        }
    }

    private static boolean unusedNotification(McpSchema.JSONRPCMessage message) {
        return message instanceof McpSchema.JSONRPCNotification notification
                && DROPPED_NOTIFICATIONS.contains(notification.method());
    }

    private static String authority(URI endpoint) {
        if (endpoint.getPort() < 0) {
            return endpoint.getHost();
        }
        return endpoint.getHost() + ":" + endpoint.getPort();
    }

    private static String path(URI endpoint) {
        String rawPath = endpoint.getRawPath();
        return rawPath == null || rawPath.isBlank() ? "/mcp" : rawPath;
    }
}
