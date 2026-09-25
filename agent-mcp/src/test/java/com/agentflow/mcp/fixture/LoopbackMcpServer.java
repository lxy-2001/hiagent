package com.agentflow.mcp.fixture;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback Streamable HTTP MCP fixture bound to 127.0.0.1 and a random port.
 */
public final class LoopbackMcpServer implements AutoCloseable {

    public static final String PROTOCOL = "2025-11-25";
    public static final String BEARER = "probe-token";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Scenario scenario;
    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final List<HttpExchange> openStreams = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch callReceived = new CountDownLatch(1);
    private final AtomicBoolean stopped = new AtomicBoolean();

    private final AtomicInteger postCount = new AtomicInteger();
    private final AtomicInteger getCount = new AtomicInteger();
    private final AtomicInteger initializeCount = new AtomicInteger();
    private final AtomicInteger listCount = new AtomicInteger();
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger initializedCount = new AtomicInteger();

    private volatile String sessionId;

    private LoopbackMcpServer(Scenario scenario) {
        this.scenario = scenario;
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to bind loopback MCP fixture", ex);
        }
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "loopback-mcp");
            thread.setDaemon(false);
            return thread;
        });
        this.httpServer.createContext("/mcp", this::handle);
        this.httpServer.setExecutor(executor);
        this.httpServer.start();
    }

    public static LoopbackMcpServer start(Scenario scenario) {
        return new LoopbackMcpServer(scenario);
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + port() + "/mcp");
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public int postCount() {
        return postCount.get();
    }

    public int getCount() {
        return getCount.get();
    }

    public int initializeCount() {
        return initializeCount.get();
    }

    public int listCount() {
        return listCount.get();
    }

    public int callCount() {
        return callCount.get();
    }

    public int initializedCount() {
        return initializedCount.get();
    }

    public String sessionId() {
        return sessionId;
    }

    public boolean awaitCall(Duration timeout) {
        try {
            return callReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public boolean awaitQuiet(Duration timeout, int posts, int initializes, int calls) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (postCount.get() != posts || initializeCount.get() != initializes || callCount.get() != calls) {
                return false;
            }
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        return postCount.get() == posts && initializeCount.get() == initializes && callCount.get() == calls;
    }

    public boolean executorTerminated() {
        return executor.isTerminated();
    }

    public int retainedExchangeCount() {
        return openStreams.size();
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        closed.countDown();
        httpServer.stop(0);
        for (HttpExchange exchange : openStreams) {
            closeExchange(exchange);
        }
        openStreams.clear();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Loopback MCP executor did not terminate");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping loopback MCP executor", ex);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        openStreams.add(exchange);
        try {
            String method = exchange.getRequestMethod();
            if ("POST".equals(method)) {
                postCount.incrementAndGet();
            }
            if (scenario.notFound) {
                sendEmpty(exchange, 404);
                return;
            }
            if ("GET".equals(method)) {
                handleGet(exchange);
                return;
            }
            if ("DELETE".equals(method)) {
                sendEmpty(exchange, 200);
                return;
            }
            if ("POST".equals(method)) {
                handlePost(exchange);
                return;
            }
            sendEmpty(exchange, 405);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sendEmpty(exchange, 500);
        }
        finally {
            closeExchange(exchange);
            openStreams.remove(exchange);
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException, InterruptedException {
        getCount.incrementAndGet();
        if (!scenario.allowGetSse) {
            sendEmpty(exchange, 405);
            return;
        }
        if (scenario.session && !sessionMatches(exchange)) {
            sendEmpty(exchange, 404);
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream");
        headers.set("Cache-Control", "no-cache");
        applySessionHeader(headers);
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = exchange.getResponseBody();
        if (scenario.emitListChangedOnGet) {
            writeSse(body, notificationJson("notifications/tools/list_changed"));
        }
        writeExtraNotifications(body);
        if (scenario.infiniteEvent) {
            writeInfiniteEvent(body);
            return;
        }
        closed.await(scenario.holdGet.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void handlePost(HttpExchange exchange) throws IOException, InterruptedException {
        if (scenario.requireBearer && !bearerMatches(exchange)) {
            sendEmpty(exchange, 401);
            return;
        }
        byte[] raw = readAll(exchange.getRequestBody());
        JsonNode root = JSON.readTree(raw);
        String rpcMethod = text(root, "method");
        JsonNode id = root.get("id");

        if ("notifications/initialized".equals(rpcMethod)) {
            initializedCount.incrementAndGet();
            sendEmpty(exchange, 202);
            return;
        }
        if ("initialize".equals(rpcMethod)) {
            initializeCount.incrementAndGet();
            if (scenario.session && sessionId == null) {
                sessionId = UUID.randomUUID().toString();
            }
            writeMessage(exchange, rpcEnvelope(id, true, initializeResultJson()));
            return;
        }
        if (scenario.session && !sessionMatches(exchange)) {
            sendEmpty(exchange, 404);
            return;
        }
        if ("tools/list".equals(rpcMethod)) {
            listCount.incrementAndGet();
            writeMessage(exchange, rpcEnvelope(id, true, listResultJson()));
            return;
        }
        if ("tools/call".equals(rpcMethod)) {
            callCount.incrementAndGet();
            callReceived.countDown();
            if (scenario.unauthorizedOnCall) {
                sendEmpty(exchange, 401);
                return;
            }
            if (scenario.sessionGoneOnCall) {
                sendEmpty(exchange, 404);
                return;
            }
            if (!scenario.callDelay.isZero()) {
                Thread.sleep(scenario.callDelay.toMillis());
            }
            if (scenario.dropAfterCallHeaders) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/json");
                applySessionHeader(headers);
                exchange.sendResponseHeaders(200, 0);
                return;
            }
            if (scenario.dropSseAfterCallHeaders) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "text/event-stream");
                headers.set("Cache-Control", "no-cache");
                applySessionHeader(headers);
                exchange.sendResponseHeaders(200, 0);
                OutputStream body = exchange.getResponseBody();
                body.write("event: message\ndata: ".getBytes(StandardCharsets.UTF_8));
                body.flush();
                return;
            }
            writeMessage(exchange, rpcEnvelope(id, true, callResultJson()));
            return;
        }
        writeMessage(exchange, rpcEnvelope(id, false, "{\"code\":-32601,\"message\":\"Method not found\"}"));
    }

    private void writeMessage(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        applySessionHeader(headers);
        if (scenario.sse) {
            headers.set("Content-Type", "text/event-stream");
            headers.set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            if (scenario.infiniteEvent) {
                writeInfiniteEvent(body);
                return;
            }
            if (scenario.emitListChangedInPostStream) {
                writeSse(body, notificationJson("notifications/tools/list_changed"));
            }
            writeSse(body, payload);
            writeExtraNotifications(body);
            body.close();
            return;
        }
        headers.set("Content-Type", "application/json");
        boolean chunked = scenario.omitContentLength || scenario.oversizedChunkedJson;
        long length = chunked ? 0 : bytes.length;
        exchange.sendResponseHeaders(200, length);
        OutputStream body = exchange.getResponseBody();
        body.write(bytes);
        body.flush();
    }

    private void writeExtraNotifications(OutputStream body) throws IOException {
        for (int i = 0; i < scenario.extraSseNotifications; i++) {
            writeSse(body, notificationJson("notifications/message"));
        }
    }

    private void writeInfiniteEvent(OutputStream body) throws IOException {
        body.write("event: message\ndata: ".getBytes(StandardCharsets.UTF_8));
        byte[] chunk = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);
        try {
            while (!closed.await(1, TimeUnit.MILLISECONDS)) {
                body.write(chunk);
                body.flush();
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeSse(OutputStream body, String json) throws IOException {
        body.write(("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static void closeExchange(HttpExchange exchange) {
        try {
            exchange.getRequestBody().close();
        }
        catch (IOException | RuntimeException ignored) {
            // fixture shutdown
        }
        try {
            exchange.getResponseBody().close();
        }
        catch (IOException | RuntimeException ignored) {
            // fixture shutdown
        }
        try {
            exchange.close();
        }
        catch (RuntimeException ignored) {
            // fixture shutdown
        }
    }

    private void applySessionHeader(Headers headers) {
        if (scenario.session && sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
    }

    private boolean sessionMatches(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        return sessionId != null && sessionId.equals(header);
    }

    private boolean bearerMatches(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return ("Bearer " + scenario.bearer).equals(header);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    private static String rpcEnvelope(JsonNode id, boolean result, String body) {
        String idJson = id == null || id.isNull() ? "null" : id.toString();
        String field = result ? "result" : "error";
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"" + field + "\":" + body + "}";
    }

    private String initializeResultJson() {
        String instructions = scenario.oversizedBodyBytes > 0 ? "x".repeat(scenario.oversizedBodyBytes) : "";
        return "{\"protocolVersion\":\"" + scenario.protocolVersion + "\","
                + "\"capabilities\":{\"tools\":{\"listChanged\":true}},"
                + "\"serverInfo\":{\"name\":\"loopback\",\"version\":\"1.0.0\"},"
                + "\"instructions\":\"" + instructions + "\"}";
    }

    private static String listResultJson() {
        return "{\"tools\":[{\"name\":\"project_info\",\"description\":\"read-only fixture\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"required\":[],"
                + "\"additionalProperties\":false}}]}";
    }

    private static String callResultJson() {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}";
    }

    private static String notificationJson(String method) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{}}";
    }

    public static final class Scenario {
        private boolean sse;
        private boolean session;
        private boolean allowGetSse;
        private boolean requireBearer;
        private String bearer = BEARER;
        private String protocolVersion = PROTOCOL;
        private boolean notFound;
        private boolean dropAfterCallHeaders;
        private boolean dropSseAfterCallHeaders;
        private boolean unauthorizedOnCall;
        private boolean sessionGoneOnCall;
        private boolean oversizedChunkedJson;
        private int oversizedBodyBytes;
        private boolean infiniteEvent;
        private boolean omitContentLength;
        private boolean emitListChangedOnGet;
        private boolean emitListChangedInPostStream;
        private int extraSseNotifications;
        private Duration callDelay = Duration.ZERO;
        private Duration holdGet = Duration.ofSeconds(2);

        public static Scenario json() {
            return new Scenario();
        }

        public Scenario sse() {
            this.sse = true;
            return this;
        }

        public Scenario session() {
            this.session = true;
            return this;
        }

        public Scenario allowGetSse() {
            this.allowGetSse = true;
            return this;
        }

        public Scenario requireBearer() {
            this.requireBearer = true;
            return this;
        }

        public Scenario protocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public Scenario notFound() {
            this.notFound = true;
            return this;
        }

        public Scenario dropAfterCallHeaders() {
            this.dropAfterCallHeaders = true;
            return this;
        }

        public Scenario dropSseAfterCallHeaders() {
            this.dropSseAfterCallHeaders = true;
            return this;
        }

        public Scenario unauthorizedOnCall() {
            this.unauthorizedOnCall = true;
            return this;
        }

        public Scenario sessionGoneOnCall() {
            this.sessionGoneOnCall = true;
            return this;
        }

        public Scenario oversizedBodyBytes(int oversizedBodyBytes) {
            this.oversizedBodyBytes = oversizedBodyBytes;
            return this;
        }

        public Scenario oversizedChunkedJson(int oversizedBodyBytes) {
            this.oversizedBodyBytes = oversizedBodyBytes;
            this.oversizedChunkedJson = true;
            this.omitContentLength = true;
            return this;
        }

        public Scenario infiniteEvent() {
            this.infiniteEvent = true;
            return this;
        }

        public Scenario omitContentLength() {
            this.omitContentLength = true;
            return this;
        }

        public Scenario emitListChangedOnGet() {
            this.emitListChangedOnGet = true;
            return this;
        }

        public Scenario emitListChangedInPostStream() {
            this.emitListChangedInPostStream = true;
            return this;
        }

        public Scenario extraSseNotifications(int extraSseNotifications) {
            this.extraSseNotifications = extraSseNotifications;
            return this;
        }

        public Scenario callDelay(Duration callDelay) {
            this.callDelay = callDelay;
            return this;
        }

        public Scenario holdGet(Duration holdGet) {
            this.holdGet = holdGet;
            return this;
        }
    }
}
