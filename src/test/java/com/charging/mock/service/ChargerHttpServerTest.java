package com.charging.mock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChargerHttpServer}.
 *
 * <p>Tests the lifecycle and request handling of the lightweight HTTP server
 * that receives push notifications from the backend. Uses OkHttp as the test
 * HTTP client (already a project dependency).
 *
 * <p>Tests are ordered to manage port 8081 lifecycle.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChargerHttpServerTest {

    private ChargerHttpServer server;
    private OkHttpClient httpClient;
    private ObjectMapper objectMapper;

    private static final String BASE_URL = "http://localhost:8081";

    @BeforeEach
    void setUp() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        objectMapper = new ObjectMapper();
        server = new ChargerHttpServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Test
    @Order(1)
    void start_shouldBindToPort8081() {
        assertDoesNotThrow(() -> {
            String json = objectMapper.writeValueAsString(Map.of(
                    "chargerCode", "TEST",
                    "recordId", "test-001",
                    "chargerType", "FAST"
            ));
            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/notify/start")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                assertNotNull(response);
            }
        });
    }

    @Test
    @Order(2)
    void start_whenPortAlreadyInUse_shouldHandleGracefully() {
        // Stop the shared server from setUp
        server.stop();

        // Bind port 8081 with a first server
        ChargerHttpServer firstServer = new ChargerHttpServer();
        firstServer.start();

        // Second server should handle port conflict gracefully
        ChargerHttpServer secondServer = null;
        try {
            secondServer = new ChargerHttpServer();
            assertNotNull(secondServer);
            secondServer.start();
        } finally {
            if (secondServer != null) {
                secondServer.stop();
            }
            firstServer.stop();
        }

        // Recreate the shared server for subsequent tests after port is released
        server = new ChargerHttpServer();
        server.start();
    }

    @Test
    @Order(3)
    void start_and_stop_shouldCleanUp() throws Exception {
        server.stop();

        ChargerHttpServer testServer = new ChargerHttpServer();
        testServer.start();
        assertNotNull(testServer);

        // Stop should not throw
        testServer.stop();

        // After stop, a new server should be able to bind on the same port
        ChargerHttpServer newServer = null;
        try {
            newServer = new ChargerHttpServer();
            newServer.start();
        } finally {
            if (newServer != null) {
                newServer.stop();
            }
        }

        // Recreate the shared server for subsequent tests
        server = new ChargerHttpServer();
        server.start();
    }

    @Test
    @Order(4)
    void notifyStart_endpoint_shouldRespondWithOk() throws Exception {
        AtomicReference<String> capturedChargerCode = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        server.setOnStartCallback(notification -> {
            capturedChargerCode.set(notification.getChargerCode());
            latch.countDown();
        });

        String json = objectMapper.writeValueAsString(Map.of(
                "chargerCode", "CY-A01",
                "recordId", "record-001",
                "chargerType", "FAST"
        ));

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/notify/start")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String body = response.body() != null ? response.body().string() : "";
            assertTrue(body.contains("ok"));
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Callback should be invoked within timeout");
        assertEquals("CY-A01", capturedChargerCode.get());
    }

    @Test
    @Order(5)
    void notifyStart_endpoint_withRecordId_shouldPassRecordId() throws Exception {
        AtomicReference<String> capturedRecordId = new AtomicReference<>();
        AtomicReference<String> capturedChargerType = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        server.setOnStartCallback(notification -> {
            capturedRecordId.set(notification.getRecordId());
            capturedChargerType.set(notification.getChargerType());
            latch.countDown();
        });

        String json = objectMapper.writeValueAsString(Map.of(
                "chargerCode", "CY-A01",
                "recordId", "record-999",
                "chargerType", "SLOW"
        ));

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/notify/start")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Callback should be invoked within timeout");
        assertEquals("record-999", capturedRecordId.get());
        assertEquals("SLOW", capturedChargerType.get());
    }

    @Test
    @Order(6)
    void notifyStop_endpoint_shouldRespondWithOk() throws Exception {
        AtomicReference<String> capturedRecordId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        server.setOnStopCallback(notification -> {
            capturedRecordId.set(notification.getRecordId());
            latch.countDown();
        });

        String json = objectMapper.writeValueAsString(Map.of(
                "chargerCode", "CY-A01",
                "recordId", "record-001"
        ));

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/notify/stop")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(200, response.code());
            String body = response.body() != null ? response.body().string() : "";
            assertTrue(body.contains("ok"));
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Callback should be invoked within timeout");
        assertEquals("record-001", capturedRecordId.get());
    }

    @Test
    @Order(7)
    void notifyStart_endpoint_withWrongMethod_shouldRespond405() throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/notify/start")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(405, response.code());
            String body = response.body() != null ? response.body().string() : "";
            assertTrue(body.contains("Method not allowed"));
        }
    }

    @Test
    @Order(8)
    void notifyStop_endpoint_withWrongMethod_shouldRespond405() throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/notify/stop")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(405, response.code());
            String body = response.body() != null ? response.body().string() : "";
            assertTrue(body.contains("Method not allowed"));
        }
    }

    @Test
    @Order(9)
    void notifyStart_endpoint_withInvalidJson_shouldRespond500() throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/notify/start")
                .post(RequestBody.create("not-valid-json",
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(500, response.code());
        }
    }

    @Test
    @Order(10)
    void server_withUnknownEndpoint_shouldRespond404() throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/notify/unknown")
                .post(RequestBody.create("{}", MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(404, response.code());
        }
    }
}
