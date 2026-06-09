package com.charging.mock.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * ChargerHttpServer is a lightweight HTTP server that receives push notifications
 * from the backend (or a test harness) to start/stop charging sessions.
 *
 * <p>It listens on {@code localhost:8081} and exposes:
 * <ul>
 *   <li>{@code POST /api/notify/start} — start a charging session</li>
 *   <li>{@code POST /api/notify/stop} — stop a charging session</li>
 * </ul>
 *
 * <p>Uses JDK built-in {@link HttpServer} (com.sun.net.httpserver) with no
 * external dependencies. Callbacks are invoked on the server's worker thread;
 * callers must dispatch to the EDT if needed via {@link javax.swing.SwingUtilities#invokeLater(Runnable)}.
 */
public class ChargerHttpServer {

    private static final int PORT = 8081;
    private static final String PATH_START = "/api/notify/start";
    private static final String PATH_STOP = "/api/notify/stop";

    private final HttpServer server;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    private Consumer<StartNotification> onStartCallback;
    private Consumer<StopNotification> onStopCallback;

    /**
     * Create the HTTP server bound to localhost:8081 but not yet started.
     * Call {@link #start()} to begin accepting connections.
     *
     * @throws RuntimeException if the server cannot be created for reasons
     *         other than port conflict
     */
    public ChargerHttpServer() {
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "charger-http-server");
            t.setDaemon(true);
            return t;
        });

        HttpServer srv;
        try {
            srv = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        } catch (BindException e) {
            System.out.println("[ChargerHttpServer] Port " + PORT
                    + " already in use (another instance running?). Continuing without HTTP server.");
            srv = null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create ChargerHttpServer on port " + PORT, e);
        }
        this.server = srv;

        if (server != null) {
            server.createContext(PATH_START, this::handleStart);
            server.createContext(PATH_STOP, this::handleStop);
            server.setExecutor(executor);
        }
    }

    // ===== Lifecycle =====

    /**
     * Start the HTTP server. Safe to call if the server was not created
     * (port conflict) — it will be a no-op.
     */
    public void start() {
        if (server == null) {
            System.out.println("[ChargerHttpServer] Server not available (port conflict). start() is no-op.");
            return;
        }
        server.start();
        System.out.println("[ChargerHttpServer] Listening on http://localhost:" + PORT);
    }

    /**
     * Stop the HTTP server gracefully. Safe to call if not running.
     */
    public void stop() {
        if (server != null) {
            server.stop(1); // 1 second max wait for pending requests
            System.out.println("[ChargerHttpServer] Stopped");
        }
        executor.shutdownNow();
    }

    // ===== Callback registration =====

    public void setOnStartCallback(Consumer<StartNotification> callback) {
        this.onStartCallback = callback;
    }

    public void setOnStopCallback(Consumer<StopNotification> callback) {
        this.onStopCallback = callback;
    }

    // ===== Request handlers =====

    private void handleStart(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            StartNotification notification = parseBody(exchange, StartNotification.class);
            System.out.println("[ChargerHttpServer] Received start notification: "
                    + "chargerCode=" + notification.getChargerCode()
                    + ", recordId=" + notification.getRecordId()
                    + ", chargerType=" + notification.getChargerType());

            if (onStartCallback != null) {
                onStartCallback.accept(notification);
            }

            respond(exchange, 200, "{\"status\":\"ok\"}");
        } catch (Exception e) {
            System.out.println("[ChargerHttpServer] Error handling start: " + e.getMessage());
            respond(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleStop(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            StopNotification notification = parseBody(exchange, StopNotification.class);
            System.out.println("[ChargerHttpServer] Received stop notification: "
                    + "chargerCode=" + notification.getChargerCode()
                    + ", recordId=" + notification.getRecordId());

            if (onStopCallback != null) {
                onStopCallback.accept(notification);
            }

            respond(exchange, 200, "{\"status\":\"ok\"}");
        } catch (Exception e) {
            System.out.println("[ChargerHttpServer] Error handling stop: " + e.getMessage());
            respond(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ===== Helpers =====

    @SuppressWarnings("unchecked")
    private <T> T parseBody(HttpExchange exchange, Class<T> clazz) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            System.out.println("[ChargerHttpServer] Raw body: " + json);
            // Use a two-step parse to handle unknown fields gracefully
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            // Convert to the target type
            String json2 = objectMapper.writeValueAsString(map);
            return objectMapper.readValue(json2, clazz);
        }
    }

    private void respond(HttpExchange exchange, int statusCode, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            System.out.println("[ChargerHttpServer] Failed to send response: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    // ===== Notification value objects =====

    /**
     * Notification payload for a start-charge request.
     */
    public static class StartNotification {
        private String chargerCode;
        private String recordId;
        private String chargerType;

        public StartNotification() {
        }

        public String getChargerCode() {
            return chargerCode;
        }

        public void setChargerCode(String chargerCode) {
            this.chargerCode = chargerCode;
        }

        public String getRecordId() {
            return recordId;
        }

        public void setRecordId(String recordId) {
            this.recordId = recordId;
        }

        public String getChargerType() {
            return chargerType;
        }

        public void setChargerType(String chargerType) {
            this.chargerType = chargerType;
        }

        @Override
        public String toString() {
            return "StartNotification{chargerCode='" + chargerCode + "', recordId='" + recordId
                    + "', chargerType='" + chargerType + "'}";
        }
    }

    /**
     * Notification payload for a stop-charge request.
     */
    public static class StopNotification {
        private String chargerCode;
        private String recordId;

        public StopNotification() {
        }

        public String getChargerCode() {
            return chargerCode;
        }

        public void setChargerCode(String chargerCode) {
            this.chargerCode = chargerCode;
        }

        public String getRecordId() {
            return recordId;
        }

        public void setRecordId(String recordId) {
            this.recordId = recordId;
        }

        @Override
        public String toString() {
            return "StopNotification{chargerCode='" + chargerCode + "', recordId='" + recordId + "'}";
        }
    }
}
