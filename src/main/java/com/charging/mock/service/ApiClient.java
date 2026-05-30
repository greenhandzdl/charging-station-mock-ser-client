package com.charging.mock.service;

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
     * @param username login username / phone
     * @param password login password
     * @return the JWT access token
     * @throws IOException          if the HTTP request fails
     * @throws ApiException         if the backend returns a non-2xx response
     * @throws IllegalStateException if the response body is empty
     */
    public String login(String username, String password) throws IOException, ApiException {
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

    // ===== Chargers =====

    /**
     * Fetch the list of available chargers from the backend.
     *
     * @return list of charger maps (id, chargerCode, status, type, stationId)
     * @throws IOException  if the HTTP request fails
     * @throws ApiException if the backend returns a non-2xx response
     */
    public List<Map<String, Object>> getChargers() throws IOException, ApiException {
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
        // GET /api/v1/charges?recordId=xxx  — filtered query
        HttpUrl url = HttpUrl.parse(baseUrl + "/charges")
                .newBuilder()
                .addQueryParameter("recordId", recordId)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + authToken)
                .build();

        List<ChargeRecord> records = executeListRecordRequest(request);
        if (records.isEmpty()) {
            throw new ApiException("Charge record not found", 404, "recordId=" + recordId);
        }
        return records.get(0);
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