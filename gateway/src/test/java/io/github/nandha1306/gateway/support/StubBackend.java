package io.github.nandha1306.gateway.support;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A real HTTP backend for tests, so no test depends on whatever is running on the developer's machine: echoes each
 * request back as JSON, fails on /api/products and is slow on /api/orders/slow.
 */
public final class StubBackend {
    public static final long SLOW_RESPONSE_MS = 1_500;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor;

    private StubBackend(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Starts the stub on a free local port.
     */
    public static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/", StubBackend::echo);
            server.createContext("/api/products", StubBackend::fail);
            server.createContext("/api/orders/slow", StubBackend::slow);
            server.start();
            return new StubBackend(server, executor);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start stub backend", e);
        }
    }

    /**
     * Returns the base URL to configure as the upstream.
     */
    public String baseUrl() {
        return "http://" + authority();
    }

    /**
     * Returns the host and port the gateway should report in X-Served-By.
     */
    public String authority() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Stops the stub and its threads.
     */
    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void echo(HttpExchange exchange) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", exchange.getRequestMethod());
        body.put("uri", exchange.getRequestURI().toString());
        body.put("headers", headers);
        body.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        exchange.getResponseHeaders().add("X-Instance-Id", "stub-1");
        exchange.getResponseHeaders().add("X-Backend", "stub");
        exchange.getResponseHeaders().add("X-RateLimit-Remaining", "999");
        exchange.getResponseHeaders().add("X-Gateway-Id", "spoofed-by-backend");
        exchange.getResponseHeaders().add("Keep-Alive", "timeout=5");
        exchange.getResponseHeaders().add("Set-Cookie", "a=1");
        exchange.getResponseHeaders().add("Set-Cookie", "b=2");
        respond(exchange, 200, JSON.writeValueAsBytes(body));
    }

    private static void fail(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("X-Instance-Id", "stub-1");
        respond(exchange, 503, "{\"error\":\"chaos_failure\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static void slow(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(SLOW_RESPONSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
