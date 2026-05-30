package com.charging.mock.config;

/**
 * Application configuration for the Mock Charger Client.
 * Backend URL, mock credentials, and QR code settings are all configurable.
 *
 * Defaults:
 * - BACKEND_URL: http://localhost:8080/api/v1
 * - MOCK_USER: mock_user / mock123
 * - QR_CODE_SIZE: 200x200
 */
public final class AppConfig {

    private AppConfig() {
    }

    public static final String BACKEND_URL = getEnvOrDefault("BACKEND_URL", "http://localhost:8080/api/v1");

    public static final String MOCK_USER_USERNAME = getEnvOrDefault("MOCK_USER", "mock_user");

    public static final String MOCK_USER_PASSWORD = getEnvOrDefault("MOCK_PASSWORD", "mock123");

    public static final int QR_CODE_WIDTH = getEnvIntOrDefault("QR_CODE_WIDTH", 200);

    public static final int QR_CODE_HEIGHT = getEnvIntOrDefault("QR_CODE_HEIGHT", 200);

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private static int getEnvIntOrDefault(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid integer for env " + key + "=\"" + value + "\", using default " + defaultValue);
            }
        }
        return defaultValue;
    }
}