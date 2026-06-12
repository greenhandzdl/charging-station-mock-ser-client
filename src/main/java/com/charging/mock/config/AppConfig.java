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

    public static final String MOCK_USER_USERNAME = getEnvOrDefault("MOCK_USER", "mock_user");

    public static final String MOCK_USER_PASSWORD = getEnvOrDefault("MOCK_PASSWORD", "mock123");

    /** 充电桩站登录账号（charger_users 表的 login_id 字段） */
    public static final String CHARGER_LOGIN_ID = getEnvOrDefault("CHARGER_LOGIN_ID", "station_global");

    /** 充电桩站登录密码（charger_users 表的密码，默认 dev123） */
    public static final String CHARGER_PASSWORD = getEnvOrDefault("CHARGER_PASSWORD", "dev123");

    /** 是否使用充电桩站身份登录（charger-login endpoint）而非用户身份 */
    public static final boolean USE_CHARGER_AUTH = getEnvBoolOrDefault("USE_CHARGER_AUTH", true);

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

    private static boolean getEnvBoolOrDefault(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }
}
