package com.charging.mock.config;

/**
 * Application configuration for the Mock Charger Client.
 * Backend URL, mock credentials, and QR code settings are all configurable.
 *
 * <p>Two permission modes are supported:
 * <ul>
 *   <li><b>Normal</b> (default) — mock_user / mock123 login with JWT authentication</li>
 *   <li><b>Advanced</b> — enabled when {@code ADVANCED_API_KEY} environment variable is set;
 *       uses API Key authentication for elevated privileges (see all chargers across stations)</li>
 * </ul>
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

    public static final String MOCK_USER_USERNAME = getEnvOrDefault("MOCK_USER", "mock_charger");

    public static final String MOCK_USER_PASSWORD = getEnvOrDefault("MOCK_PASSWORD", "charger123");

    public static final String DEVICE_TOKEN = "dev_token_c001";

    public static final int QR_CODE_WIDTH = getEnvIntOrDefault("QR_CODE_WIDTH", 200);

    public static final int QR_CODE_HEIGHT = getEnvIntOrDefault("QR_CODE_HEIGHT", 200);

    // ===== Advanced permission mode =====

    /**
     * Advanced API key read from environment variable.
     * When non-empty, the client switches to advanced mode with elevated privileges.
     */
    public static final String ADVANCED_API_KEY = getEnv("ADVANCED_API_KEY");

    /**
     * Whether the client is operating in advanced permission mode.
     * Advanced mode is activated when {@link #ADVANCED_API_KEY} is set.
     */
    public static final boolean IS_ADVANCED_MODE = !ADVANCED_API_KEY.isEmpty();

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

    private static String getEnv(String key) {
        String value = System.getenv(key);
        return value != null ? value : "";
    }
}
