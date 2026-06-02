package com.charging.mock.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides local test data for chargers, simulating the charger's built-in
 * hardware configuration. No backend API call is needed for this data.
 *
 * <p>The Mock charger screen uses these hardcoded values to display available
 * chargers, generate QR codes with charger ID for Flutter to scan, and
 * simulate plug/unplug interactions.</p>
 */
public final class TestDataProvider {

    private static final List<Map<String, Object>> TEST_CHARGERS = new ArrayList<>();

    static {
        addCharger("11111111-1111-1111-1111-111111111001", "CY-A01", "fast", "idle", "朝阳站A区1号");
        addCharger("11111111-1111-1111-1111-111111111002", "CY-A02", "slow", "idle", "朝阳站A区2号");
        addCharger("11111111-1111-1111-1111-111111111003", "CY-B01", "fast", "idle", "朝阳站B区1号");
        addCharger("22222222-2222-2222-2222-222222222001", "HD-A01", "fast", "idle", "海淀站A区1号");
        addCharger("22222222-2222-2222-2222-222222222002", "HD-A02", "slow", "idle", "海淀站A区2号");
        addCharger("33333333-3333-3333-3333-333333333001", "XC-A01", "fast", "idle", "西城站A区1号");
        addCharger("33333333-3333-3333-3333-333333333002", "XC-B01", "fast", "idle", "西城站B区1号");
    }

    private static void addCharger(String id, String code, String type, String status, String stationName) {
        Map<String, Object> charger = new HashMap<>();
        charger.put("id", id);
        charger.put("chargerCode", code);
        charger.put("type", type);
        charger.put("status", status);
        charger.put("stationName", stationName);
        TEST_CHARGERS.add(charger);
    }

    public static List<Map<String, Object>> getChargers() {
        return new ArrayList<>(TEST_CHARGERS);
    }

    public static Map<String, Object> getChargerById(String chargerId) {
        return TEST_CHARGERS.stream()
                .filter(c -> c.get("id").equals(chargerId))
                .findFirst()
                .orElse(null);
    }

    public static Map<String, Object> getChargerByCode(String code) {
        return TEST_CHARGERS.stream()
                .filter(c -> c.get("chargerCode").equals(code))
                .findFirst()
                .orElse(null);
    }

    private TestDataProvider() {
    }
}