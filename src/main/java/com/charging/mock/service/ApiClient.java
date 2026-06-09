package com.charging.mock.service;

import com.charging.mock.config.AppConfig;
import com.charging.mock.config.NetworkSimulator;
import com.charging.mock.model.ChargeRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ApiClient handles all HTTP communication with the backend Spring Boot API.
 *
 * <p>Provides methods for authentication (login to obtain a JWT),
 * charger listing, and charge start/stop/status operations.
 * JSON serialization/deserialization uses Jackson with JSR-310 support.
 *
 * <p>Security: The JWT token obtained at login is automatically attached
 * as a Bearer token to all subsequent requests. The token scope is
 * {@code mock_charger_only}, meaning only {@code /api/v1/charges/*} endpoints
 * are accessible — the backend Nginx layer enforces this at the network level
 * and Spring Security enforces it at the application level.
 *
 * <p>Two authentication modes are supported:
 * <ul>
 *   <li><b>Normal</b> — username/password login via {@code POST /auth/login}</li>
 *   <li><b>Advanced</b> — API Key authentication via {@link #authenticateWithApiKey(String)},
 *       activated when {@link AppConfig#ADVANCED_API_KEY} is set</li>
 * </ul>
 */
public class ApiClient {

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String authToken;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ===== Authentication =====

    /**
     * Authenticate with the backend and store the returned JWT token.
     *
     * <p>If {@link AppConfig#ADVANCED_API_KEY} is set, delegates to
     * {@link #authenticateWithApiKey(String)} for advanced authentication.
     * Otherwise, performs normal username/password login.
     *
     * @param username login username / phone
     * @param password login password
     * @return the JWT access token
     * @throws IOException          if the HTTP request fails
     * @throws ApiException         if the backend returns a non-2xx response
     * @throws IllegalStateException if the response body is empty
     */
    public String login(String username, String password) throws IOException, ApiException {
        // If advanced API key is configured, use API Key authentication instead
        if (AppConfig.IS_ADVANCED_MODE) {
            return authenticateWithApiKey(AppConfig.ADVANCED_API_KEY);
        }

        // Normal username/password login
        checkOffline();
        Map<String, String> body = new HashMap<>();
        body.put("phone", username);
        body.put("password", password);

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/auth/login")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        Response response = httpClient.newCall(request).execute();
        try (ResponseBody responseBody = response.body()) {
            String responseStr = (responseBody != null) ? responseBody.string() : "";

            if (!response.isSuccessful()) {
                throw new ApiException("Login failed", response.code(), responseStr);
            }
            if (responseStr.isEmpty()) {
                throw new IllegalStateException("Login response body is empty");
            }

            Map<String, Object> result = objectMapper.readValue(responseStr,
                    new TypeReference<Map<String, Object>>() {
                    });
            String token = (String) result.get("accessToken");
            if (token == null || token.isBlank()) {
                token = (String) result.get("token");
            }
            if (token == null || token.isBlank()) {
                throw new ApiException("No token in login response", response.code(), responseStr);
            }
            this.authToken = token;
            return token;
        }
    }

    /**
     * Authenticate using an API Key for advanced permission mode.
     *
     * <p>Sends the API key via {@code Authorization: Bearer <apiKey>} header to
     * the {@code /auth/advanced-login} endpoint. The backend verifies the key
     * and returns a scoped JWT token with elevated privileges.
     *
     * <p>If the advanced-login endpoint is not yet implemented, falls back to
     * sending the API key as the password to the normal login endpoint with
     * a special scope marker in the username field.
     *
     * @param apiKey the advanced API key
     * @return the JWT access token
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public String authenticateWithApiKey(String apiKey) throws IOException, ApiException {
        checkOffline();

        // Try the dedicated advanced-login endpoint first
        Request request = new Request.Builder()
                .url(baseUrl + "/auth/advanced-login")
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            try (ResponseBody responseBody = response.body()) {
                String responseStr = (responseBody != null) ? responseBody.string() : "";

                // If the endpoint returns 404, fall back to normal login with api key as password
                if (response.code() == 404) {
                    System.out.println("[ApiClient] /auth/advanced-login not available, falling back to normal login with scope marker");
                    return loginWithApiKeyFallback(apiKey);
                }

                if (!response.isSuccessful()) {
                    throw new ApiException("Advanced login failed", response.code(), responseStr);
                }
                if (responseStr.isEmpty()) {
                    throw new IllegalStateException("Advanced login response body is empty");
                }

                Map<String, Object> result = objectMapper.readValue(responseStr,
                        new TypeReference<Map<String, Object>>() {
                        });
                String token = (String) result.get("accessToken");
                if (token == null || token.isBlank()) {
                    token = (String) result.get("token");
                }
                if (token == null || token.isBlank()) {
                    throw new ApiException("No token in advanced login response", response.code(), responseStr);
                }
                this.authToken = token;
                System.out.println("[ApiClient] Advanced login successful via /auth/advanced-login");
                return token;
            }
        }
    }

    /**
     * Fallback: use the normal login endpoint with the API key as password,
     * using a special username "advanced_scope" to signal elevated permissions.
     */
    private String loginWithApiKeyFallback(String apiKey) throws IOException, ApiException {
        Map<String, String> body = new HashMap<>();
        body.put("phone", "advanced_scope");
        body.put("password", apiKey);

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/auth/login")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        Response response = httpClient.newCall(request).execute();
        try (ResponseBody responseBody = response.body()) {
            String responseStr = (responseBody != null) ? responseBody.string() : "";

            if (!response.isSuccessful()) {
                throw new ApiException("Advanced login fallback failed", response.code(), responseStr);
            }
            if (responseStr.isEmpty()) {
                throw new IllegalStateException("Advanced login fallback response body is empty");
            }

            Map<String, Object> result = objectMapper.readValue(responseStr,
                    new TypeReference<Map<String, Object>>() {
                    });
            String token = (String) result.get("accessToken");
            if (token == null || token.isBlank()) {
                token = (String) result.get("token");
            }
            if (token == null || token.isBlank()) {
                throw new ApiException("No token in advanced login fallback response", response.code(), responseStr);
            }
            this.authToken = token;
            System.out.println("[ApiClient] Advanced login successful via fallback (scope marker)");
            return token;
        }
    }

    /**
     * Check whether the current authentication is in advanced mode.
     */
    public static boolean isAdvancedMode() {
        return AppConfig.IS_ADVANCED_MODE;
    }

    // ===== Chargers =====

    /**
     * Fetch the list of available chargers from the backend.
     *
     * @return list of charger maps (id, chargerCode, status, type, stationId)
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public List<Map<String, Object>> getChargers() throws IOException, ApiException {
        checkOffline();
        Request request = new Request.Builder()
                .url(baseUrl + "/chargers")
                .get()
                .addHeader("Authorization", "Bearer " + authToken)
                .build();

        return executeListRequest(request);
    }

    // ===== Charge Operations =====

    /**
     * Start a charging session on the given charger.
     *
     * @param chargerId the UUID of the charger to use
     * @return the created {@link ChargeRecord}
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public ChargeRecord startCharge(String chargerId) throws IOException, ApiException {
        checkOffline();
        Map<String, String> body = new HashMap<>();
        body.put("chargerId", chargerId);

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/charges/start")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + authToken)
                .build();

        return executeRecordRequest(request);
    }

    /**
     * Stop an active charging session.
     *
     * @param recordId the UUID of the charge record to stop
     * @return the completed {@link ChargeRecord}
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public ChargeRecord stopCharge(String recordId) throws IOException, ApiException {
        checkOffline();
        Map<String, String> body = new HashMap<>();
        body.put("recordId", recordId);

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/charges/stop")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + authToken)
                .build();

        return executeRecordRequest(request);
    }

    /**
     * Get the current status of a charge record.
     *
     * @param recordId the UUID of the charge record
     * @return the {@link ChargeRecord} with current status
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public ChargeRecord getChargeStatus(String recordId) throws IOException, ApiException {
        checkOffline();
        // GET /api/v1/charges?recordId=xxx  — filtered query (backend supports recordId param)
        HttpUrl url = HttpUrl.parse(baseUrl + "/charges")
                .newBuilder()
                .addQueryParameter("recordId", recordId)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + authToken)
                .build();

        // Response is a list of enriched charge records (camelCase keys)
        List<Map<String, Object>> records = executeListRequest(request);
        if (records.isEmpty()) {
            throw new ApiException("Charge record not found", 404, "recordId=" + recordId);
        }
        Map<String, Object> first = records.get(0);
        String json = objectMapper.writeValueAsString(first);
        try {
            return objectMapper.readValue(json, ChargeRecord.class);
        } catch (Exception e) {
            // Fallback: manually map fields
            ChargeRecord cr = new ChargeRecord();
            cr.setId(toStringValue(first.get("id")));
            cr.setUserId(toStringValue(first.get("userId")));
            cr.setChargerId(toStringValue(first.get("chargerId")));
            cr.setStatus(toStringValue(first.get("status")));
            cr.setDeductionStatus(toStringValue(first.get("deductionStatus")));
            return cr;
        }
    }

    /** Helper: convert an Object from a Map to String (handles UUID, etc.) */
    private static String toStringValue(Object obj) {
        return obj != null ? obj.toString() : null;
    }



    // ===== Heartbeat =====

    /**
     * Send heartbeat to Spring backend for a specific charger.
     * POST /api/v1/chargers/heartbeat
     *
     * @param chargerCode the charger code (e.g. "CY-A01")
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public void sendHeartbeat(String chargerCode) throws IOException, ApiException {
        checkOffline();
        Map<String, String> body = new HashMap<>();
        body.put("chargerCode", chargerCode);

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/chargers/heartbeat")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            try (ResponseBody responseBody = response.body()) {
                if (!response.isSuccessful()) {
                    String responseStr = (responseBody != null) ? responseBody.string() : "";
                    throw new ApiException("Heartbeat failed for charger: " + chargerCode,
                            response.code(), responseStr);
                }
                System.out.println("[Heartbeat] Sent for charger: " + chargerCode);
            }
        }
    }

    // ===== Query charges (list) =====

    /**
     * Query charge records with optional filters.
     *
     * @return list of {@link ChargeRecord}
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public List<ChargeRecord> queryCharges() throws IOException, ApiException {
        checkOffline();
        Request request = new Request.Builder()
                .url(baseUrl + "/charges")
                .get()
                .addHeader("Authorization", "Bearer " + authToken)
                .build();

        return executeListRecordRequest(request);
    }

    // ===== Internal helpers =====

    private Map<String, Object> executeMapRequest(Request request) throws IOException, ApiException {
        try (Response response = httpClient.newCall(request).execute()) {
            try (ResponseBody body = response.body()) {
                String responseStr = (body != null) ? body.string() : "";
                if (!response.isSuccessful()) {
                    throw new ApiException("Request failed", response.code(), responseStr);
                }
                if (responseStr.isEmpty()) {
                    return new HashMap<>();
                }
                return objectMapper.readValue(responseStr,
                        new TypeReference<Map<String, Object>>() {
                        });
            }
        }
    }

    private List<Map<String, Object>> executeListRequest(Request request) throws IOException, ApiException {
        try (Response response = httpClient.newCall(request).execute()) {
            try (ResponseBody body = response.body()) {
                String responseStr = (body != null) ? body.string() : "";
                if (!response.isSuccessful()) {
                    throw new ApiException("Request failed", response.code(), responseStr);
                }
                if (responseStr.isEmpty()) {
                    return List.of();
                }
                return objectMapper.readValue(responseStr,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
            }
        }
    }

    private ChargeRecord executeRecordRequest(Request request) throws IOException, ApiException {
        try (Response response = httpClient.newCall(request).execute()) {
            try (ResponseBody body = response.body()) {
                String responseStr = (body != null) ? body.string() : "";
                if (!response.isSuccessful()) {
                    throw new ApiException("Request failed", response.code(), responseStr);
                }
                if (responseStr.isEmpty()) {
                    throw new ApiException("Empty response", response.code(), "body is empty");
                }
                return objectMapper.readValue(responseStr, ChargeRecord.class);
            }
        }
    }

    private List<ChargeRecord> executeListRecordRequest(Request request) throws IOException, ApiException {
        try (Response response = httpClient.newCall(request).execute()) {
            try (ResponseBody body = response.body()) {
                String responseStr = (body != null) ? body.string() : "";
                if (!response.isSuccessful()) {
                    throw new ApiException("Request failed", response.code(), responseStr);
                }
                if (responseStr.isEmpty()) {
                    return List.of();
                }
                return objectMapper.readValue(responseStr,
                        new TypeReference<List<ChargeRecord>>() {
                        });
            }
        }
    }

    // ===== Network offline check =====

    /**
     * Check if the network is simulated as offline and throw an IOException if so.
     * This ensures no actual HTTP calls are made when in offline simulation mode.
     */
    private static void checkOffline() throws IOException {
        if (NetworkSimulator.isOffline()) {
            throw new IOException("[NetworkSimulator] Simulated network error: backend unreachable");
        }
    }

    // ===== Accessor for authToken =====

    public boolean isAuthenticated() {
        return authToken != null && !authToken.isBlank();
    }

    public String getAuthToken() {
        return authToken;
    }

    public void clearAuthToken() {
        this.authToken = null;
    }

    /**
     * Custom exception for API errors that carries the HTTP status code
     * and the raw response body for diagnostic purposes.
     */
    public static class ApiException extends Exception {
        private final int statusCode;
        private final String responseBody;

        public ApiException(String message, int statusCode, String responseBody) {
            super(message + " (HTTP " + statusCode + "): " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
