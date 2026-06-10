package com.charging.mock;

import com.charging.mock.service.ApiClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for charger status display logic in the Mock client.
 *
 * <p>Validates:
 * <ul>
 *   <li>Charger list shows OFFLINE status when backend reports offline</li>
 *   <li>ApiClient.plugIn() sends the correct HTTP request</li>
 * </ul>
 */
class ChargerStatusTest {

    private MockWebServer mockServer;
    private ApiClient apiClient;

    @BeforeEach
    void setUp() {
        mockServer = new MockWebServer();
        apiClient = new ApiClient(mockServer.url("/api/v1").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    void chargers_showOfflineStatus_whenBackendReturnsOffline() throws Exception {
        // Arrange: login first
        arrangeLogin("test-token");
        apiClient.login("mock_user", "mock123");

        // Backend charger list returns some chargers with OFFLINE status
        String chargersJson = """
                [
                  {"id":"c1","chargerCode":"CHARGER-001","status":"IDLE","type":"FAST","onlineStatus":"OFFLINE"},
                  {"id":"c2","chargerCode":"CHARGER-002","status":"IDLE","type":"SLOW","onlineStatus":"OFFLINE"}
                ]
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(chargersJson)
                .setResponseCode(200));

        // Act
        List<Map<String, Object>> chargers = apiClient.getChargers();

        // Assert
        assertEquals(2, chargers.size());
        assertEquals("OFFLINE", chargers.get(0).get("onlineStatus"));
        assertEquals("OFFLINE", chargers.get(1).get("onlineStatus"));
        assertEquals("IDLE", chargers.get(0).get("status"));
        assertEquals("IDLE", chargers.get(1).get("status"));
    }

    @Test
    void chargers_showMixedOnlineStatus() throws Exception {
        arrangeLogin("test-token");
        apiClient.login("mock_user", "mock123");

        String chargersJson = """
                [
                  {"id":"c1","chargerCode":"C001","status":"CHARGING","type":"FAST","onlineStatus":"ONLINE"},
                  {"id":"c2","chargerCode":"C002","status":"IDLE","type":"SLOW","onlineStatus":"OFFLINE"}
                ]
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(chargersJson)
                .setResponseCode(200));

        List<Map<String, Object>> chargers = apiClient.getChargers();

        assertEquals("ONLINE", chargers.get(0).get("onlineStatus"));
        assertEquals("OFFLINE", chargers.get(1).get("onlineStatus"));
    }

    @Test
    void chargers_emptyList_whenBackendReturnsEmpty() throws Exception {
        arrangeLogin("test-token");
        apiClient.login("mock_user", "mock123");

        mockServer.enqueue(new MockResponse()
                .setBody("[]")
                .setResponseCode(200));

        List<Map<String, Object>> chargers = apiClient.getChargers();
        assertTrue(chargers.isEmpty());
    }

    @Test
    void plugIn_sendsCorrectHttpRequest() throws Exception {
        arrangeLogin("plug-in-token");
        apiClient.login("mock_user", "mock123");

        mockServer.enqueue(new MockResponse()
                .setBody("{\"status\":\"ok\"}")
                .setResponseCode(200));

        boolean result = apiClient.plugIn("record-001");

        assertTrue(result, "plugIn should return true on 200 response");

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS); // login
        request = mockServer.takeRequest(1, TimeUnit.SECONDS); // plugIn
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/charges/record-001/plug-in"),
                "Path should contain the record ID: " + request.getPath());
        assertEquals("Bearer plug-in-token", request.getHeader("Authorization"));

        String body = request.getBody().readUtf8();
        assertNotNull(body);
    }

    @Test
    void plugIn_whenBackendReturnsError_returnsFalse() throws Exception {
        arrangeLogin("test-token");
        apiClient.login("mock_user", "mock123");

        mockServer.enqueue(new MockResponse()
                .setBody("{\"error\":\"record not found\"}")
                .setResponseCode(404));

        boolean result = apiClient.plugIn("nonexistent-record");

        assertFalse(result);
    }

    @Test
    void plugIn_whenUnauthenticated_stillSendsRequest() throws Exception {
        // Without auth header, the plugIn call should still send the request
        mockServer.enqueue(new MockResponse()
                .setBody("{\"status\":\"ok\"}")
                .setResponseCode(200));

        boolean result = apiClient.plugIn("record-002");

        // If auth token is null, the request still goes through but without Bearer header
        // The backend may reject it, but the ApiClient doesn't throw on non-success
        // It depends on whether the response code is successful or not
        // Since we get 200, it should return true
        assertTrue(result);
    }

    // ===== Helpers =====

    private void arrangeLogin(String token) {
        String loginResponse = "{\"accessToken\":\"" + token + "\"}";
        mockServer.enqueue(new MockResponse()
                .setBody(loginResponse)
                .setResponseCode(200));
    }
}
