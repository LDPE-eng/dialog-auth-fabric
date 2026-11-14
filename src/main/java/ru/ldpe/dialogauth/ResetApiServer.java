package ru.ldpe.dialogauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ResetApiServer implements AutoCloseable {
    private final AccountStore accounts;
    private final ResetCodeStore resetCodes;
    private final String token;
    private final int port;
    private HttpServer server;
    private ExecutorService executor;

    public ResetApiServer(AccountStore accounts, ResetCodeStore resetCodes, String token, int port) {
        this.accounts = accounts;
        this.resetCodes = resetCodes;
        this.token = token;
        this.port = port;
    }

    public void start() throws IOException {
        if (token.length() < 32) throw new IllegalArgumentException("reset-api-token must contain at least 32 characters");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dialogauth-reset-api");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/reset", this::handle);
        server.start();
        DialogAuth.LOGGER.info("Password reset API listening on 127.0.0.1:{}", server.getAddress().getPort());
    }

    public int port() {
        if (server == null) throw new IllegalStateException("Reset API is not started");
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, "{\"ok\":false}");
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String expected = "Bearer " + token;
            if (authorization == null || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 401, "{\"ok\":false}");
                return;
            }
            if (exchange.getRequestHeaders().getFirst("Content-Length") != null
                    && Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length")) > 2048) {
                respond(exchange, 413, "{\"ok\":false}");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(2049);
            if (body.length > 2048) {
                respond(exchange, 413, "{\"ok\":false}");
                return;
            }
            JsonObject json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String username = json.get("username").getAsString();
            String code = json.get("code").getAsString();
            char[] password = json.get("password").getAsString().toCharArray();
            try {
                var uuid = accounts.findUuidByUsername(username);
                boolean reset = uuid.isPresent() && resetCodes.reset(accounts, uuid.get(), code, password);
                respond(exchange, 200, reset ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"invalid_or_expired\"}");
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (Exception e) {
            DialogAuth.LOGGER.warn("Password reset request failed: {}", e.getMessage());
            respond(exchange, 400, "{\"ok\":false}");
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }
}
