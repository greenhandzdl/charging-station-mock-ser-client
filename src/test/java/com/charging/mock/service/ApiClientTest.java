package com.charging.mock.service;

import com.charging.mock.model.ChargeRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApiClient} using OkHttp MockWebServer.
 * <p>
 * These tests validate:
 * <ul>
 *   <li>Login flow -- success, missing token, auth failure, empty body</li>
 *   <li>Start/stop charge operations -- successful response and error paths</li>
 *   <li>Charge status query -- record found and not-found cases</li>
 *   <li>Authorization header propagation after login</li>
 *   <li>Edge cases: empty response body, non-2xx status codes</li>
 * </ul>
 */
class ApiClientTest {

    private MockWebServer mockServer;
    private ApiClient apiClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockServer = new MockWebServer();
        apiClient = new ApiClient(mockServer.url("/api/v1").toString());
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    // ===== Login =====

    @Test
    void login_withValidCredentials_returnsTokenAndSetsAuthState() throws Exception {
        // Arrange
        String expectedToken = "eyJhbGciOiJIUzI1NiJ9.mock-jwt-token";
        String loginResponse = "{\"accessToken\":\"" + expectedToken + "\"}";
        mockServer.enqueue(new MockResponse()
                .setBody(loginResponse)
                .setResponseCode(200));

        // Act
        String token = apiClient.login("mock_user", "mock123");

        // Assert
        assertEquals(expectedToken, token);
        assertTrue(apiClient.isAuthenticated());
        assertEquals(expectedToken, apiClient.getAuthToken());

        // Verify request body contains phone and password
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/v1/auth/login", request.getPath());
        assertEquals("POST", request.getMethod());

        String bodyJson = request.getBody().readUtf8();
        assertTrue(bodyJson.contains("\"phone\":\"mock_user\""));
        assertTrue(bodyJson.contains("\"password\":\"mock123\""));
    }

    @Test
    void login_withFallbackTokenField_returnsToken() throws Exception {
        // Some backends return "token" instead of "accessToken"
        String expectedToken = "fallback-token-value";
        String loginResponse = "{\"token\":\"" + expectedToken + "\"}";
        mockServer.enqueue(new MockResponse()
                .setBody(loginResponse)
                .setResponseCode(200));

        String token = apiClient.login("user", "pass");

        assertEquals(expectedToken, token);
        assertTrue(apiClient.isAuthenticated());
    }

    @Test
    void login_whenResponseHasNoToken_throwsApiException() {
        // Backend returned 200 but no token field
        mockServer.enqueue(new MockResponse()
                .setBody("{\"message\":\"ok\"}")
                .setResponseCode(200));

        ApiClient.ApiException ex = assertThrows(ApiClient.ApiException.class,
                () -> apiClient.login("user", "pass"));
        assertTrue(ex.getMessage().contains("No token"));
    }

    @Test
    void login_whenResponseIsEmpty_throwsIllegalStateException() {
        mockServer.enqueue(new MockResponse()
                .setBody("")
                .setResponseCode(200));

        assertThrows(IllegalStateException.class,
                () -> apiClient.login("user", "pass"));
    }

    @Test
    void login_whenBackendReturns401_throwsApiException() {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"error\":\"bad credentials\"}")
                .setResponseCode(401));

        ApiClient.ApiException ex = assertThrows(ApiClient.ApiException.class,
                () -> apiClient.login("user", "wrong"));
        assertEquals(401, ex.getStatusCode());
        assertFalse(apiClient.isAuthenticated());
    }

    // ===== Start Charge =====

    @Test
    void startCharge_afterLogin_returnsChargeRecord() throws Exception {
        // Arrange -- login first
        arrangeLogin("my-token");
        apiClient.login("mock_user", "mock123");

        ChargeRecord expected = new ChargeRecord();
        expected.setId("record-001");
        expected.setChargerId("charger-001");
        expected.setStatus("CHARGING");
        expected.setEnergyKwh(BigDecimal.ZERO);

        mockServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(expected))
                .setResponseCode(200));

        // Act
        ChargeRecord result = apiClient.startCharge("charger-001");

        // Assert
        assertEquals("record-001", result.getId());
        assertEquals("CHARGING", result.getStatus());

        // Verify auth header was sent
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS); // login
        request = mockServer.takeRequest(1, TimeUnit.SECONDS); // start
        assertNotNull(request);
        assertEquals("Bearer my-token", request.getHeader("Authorization"));
        assertTrue(request.getBody().readUtf8().contains("\"chargerId\":\"charger-001\""));
    }

    @Test
    void startCharge_whenBackendReturns402_throwsApiExceptionWithBalanceError() {
        arrangeLogin("tk");
        loginSilently();

        mockServer.enqueue(new MockResponse()
                .setBody("{\"error\":\"insufficient balance\"}")
                .setResponseCode(402));

        ApiClient.ApiException ex = assertThrows(ApiClient.ApiException.class,
                () -> apiClient.startCharge("charger-x"));
        assertEquals(402, ex.getStatusCode());
    }

    @Test
    void startCharge_whenUnauthenticated_throwsNullPointer() {
        // Calling startCharge without login first -- no auth token set
        mockServer.enqueue(new MockResponse()
                .setBody("{\"error\":\"unauthorized\"}")
                .setResponseCode(401));

        // The ApiClient will send the request without an auth header if not logged in
        // It should fail with ApiException
        ApiClient.ApiException ex = assertThrows(ApiClient.ApiException.class,
                () -> apiClient.startCharge("charger-x"));
        assertEquals(401, ex.getStatusCode());
    }

    // ===== Stop Charge =====

    @Test
    void stopCharge_afterStart_returnsCompletedRecord() throws Exception {
        // Arrange
        arrangeLogin("tk");
        loginSilently();

        ChargeRecord completed = new ChargeRecord();
        completed.setId("record-001");
        completed.setStatus("COMPLETED");
        completed.setEnergyKwh(BigDecimal.valueOf(5.0));
        completed.setFee(BigDecimal.valueOf(7.50));
        completed.setDeductionStatus("PAID");

        mockServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(completed))
                .setResponseCode(200));

        // Act
        ChargeRecord result = apiClient.stopCharge("record-001");

        // Assert
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(0, BigDecimal.valueOf(7.50).compareTo(result.getFee()));
        assertEquals("PAID", result.getDeductionStatus());

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS); // login
        request = mockServer.takeRequest(1, TimeUnit.SECONDS); // stop
        assertNotNull(request);
        assertEquals("/api/v1/charges/stop", request.getPath());
        assertTrue(request.getBody().readUtf8().contains("\"recordId\":\"record-001\""));
    }

    // ===== Get Charge Status =====

    @Test
    void getChargeStatus_whenRecordExists_returnsRecord() throws Exception {
        arrangeLogin("tk");
        loginSilently();

        ChargeRecord record = new ChargeRecord();
        record.setId("record-001");
        record.setStatus("CHARGING");
        record.setEnergyKwh(BigDecimal.valueOf(2.5));

        // getChargeStatus returns a list
        String listJson = objectMapper.writeValueAsString(List.of(record));
        mockServer.enqueue(new MockResponse()
                .setBody(listJson)
                .setResponseCode(200));

        ChargeRecord result = apiClient.getChargeStatus("record-001");
        assertEquals("record-001", result.getId());
        assertEquals("CHARGING", result.getStatus());
    }

    @Test
    void getChargeStatus_whenRecordNotFound_throwsApiException() throws Exception {
        arrangeLogin("tk");
        loginSilently();

        mockServer.enqueue(new MockResponse()
                .setBody("[]")
                .setResponseCode(200));

        ApiClient.ApiException ex = assertThrows(ApiClient.ApiException.class,
                () -> apiClient.getChargeStatus("nonexistent"));
        assertEquals(404, ex.getStatusCode());
    }

    // ===== Query Charges =====

    @Test
    void queryCharges_returnsListOfRecords() throws Exception {
        arrangeLogin("tk");
        loginSilently();

        ChargeRecord r1 = new ChargeRecord();
        r1.setId("r1");
        r1.setStatus("COMPLETED");

        ChargeRecord r2 = new ChargeRecord();
        r2.setId("r2");
        r2.setStatus("CHARGING");

        String listJson = objectMapper.writeValueAsString(List.of(r1, r2));
        mockServer.enqueue(new MockResponse()
                .setBody(listJson)
                .setResponseCode(200));

        List<ChargeRecord> results = apiClient.queryCharges();
        assertEquals(2, results.size());
        assertEquals("r1", results.get(0).getId());
        assertEquals("r2", results.get(1).getId());
    }

    @Test
    void queryCharges_whenEmpty_returnsEmptyList() throws Exception {
        arrangeLogin("tk");
        loginSilently();

        mockServer.enqueue(new MockResponse()
                .setBody("[]")
                .setResponseCode(200));

        List<ChargeRecord> results = apiClient.queryCharges();
        assertTrue(results.isEmpty());
    }

    // ===== Get Chargers =====

    @SuppressWarnings("unchecked")
    @Test
    void getChargers_returnsListOfChargerMaps() throws Exception {
        arrangeLogin("tk");
        loginSilently();

        String chargersJson = """
                [
                  {"id":"c1","chargerCode":"CHARGER-001","status":"IDLE","type":"FAST"},
                  {"id":"c2","chargerCode":"CHARGER-002","status":"CHARGING","type":"SLOW"}
                ]
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(chargersJson)
                .setResponseCode(200));

        List<Map<String, Object>> chargers = apiClient.getChargers();
        assertEquals(2, chargers.size());
        assertEquals("c1", chargers.get(0).get("id"));
        assertEquals("IDLE", chargers.get(0).get("status"));
        assertEquals("CHARGER-002", chargers.get(1).get("chargerCode"));
    }

    @Test
    void getChargers_whenEmpty_returnsEmptyList() throws Exception {
        arrangeLogin("tk");
        loginSilently();

        mockServer.enqueue(new MockResponse()
                .setBody("[]")
                .setResponseCode(200));

        List<Map<String, Object>> chargers = apiClient.getChargers();
        assertTrue(chargers.isEmpty());
    }

    // ===== Auth state management =====

    @Test
    void clearAuthToken_resetsAuthenticationState() throws Exception {
        arrangeLogin("some-token");
        apiClient.login("u", "p");
        assertTrue(apiClient.isAuthenticated());

        apiClient.clearAuthToken();
        assertFalse(apiClient.isAuthenticated());
        assertNull(apiClient.getAuthToken());
    }

    @Test
    void isAuthenticated_returnsFalseInitially() {
        assertFalse(apiClient.isAuthenticated());
        assertNull(apiClient.getAuthToken());
    }

    // ===== Base URL trimming =====

    @Test
    void constructor_trimsTrailingSlash() throws Exception {
        MockWebServer server = new MockWebServer();
        // Enqueue: one for login (will fail with empty body), but we check the path
        server.enqueue(new MockResponse().setBody("{}").setResponseCode(401));
        ApiClient client = new ApiClient(server.url("/api/v1/").toString());

        // Force login attempt -- will get 401, but the path should be clean
        try {
            client.login("u", "p");
        } catch (ApiClient.ApiException ignored) {
        }

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        // Path should not have double slash
        assertEquals("/api/v1/auth/login", request.getPath());
        server.shutdown();
    }

    // ===== Helpers =====

    private void arrangeLogin(String token) {
        String loginResponse = "{\"accessToken\":\"" + token + "\"}";
        mockServer.enqueue(new MockResponse()
                .setBody(loginResponse)
                .setResponseCode(200));
    }

    private void loginSilently() {
        try {
            apiClient.login("mock_user", "mock123");
        } catch (Exception e) {
            throw new RuntimeException("Test setup failed during login", e);
        }
    }
}