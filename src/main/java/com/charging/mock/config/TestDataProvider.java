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
        // ID must match seed.sql UUIDs for consistent cross-system identification
        // chargers table seed data uses c0000000-... prefix:
        //   朝阳站(b000...001): CY-A01(id c000...001), CY-A02(c000...002), CY-B01(c000...003)
        //   海淀站(b000...002): HD-A01(c000...004), HD-B01(c000...005)  — note: HD-B01 not HD-A02
        //   浦东站(b000...003): PD-A01(c000...006), PD-B01(c000...007)
        addCharger("c0000000-0000-4000-8000-000000000001", "CY-A01", "FAST", "IDLE", "朝阳区充电站");
        addCharger("c0000000-0000-4000-8000-000000000002", "CY-A02", "FAST", "IDLE", "朝阳区充电站");
        addCharger("c0000000-0000-4000-8000-000000000003", "CY-B01", "SLOW", "IDLE", "朝阳区充电站");
        addCharger("c0000000-0000-4000-8000-000000000004", "HD-A01", "FAST", "IDLE", "海淀区充电站");
        addCharger("c0000000-0000-4000-8000-000000000005", "HD-B01", "SLOW", "FAULT", "海淀区充电站");
        addCharger("c0000000-0000-4000-8000-000000000006", "PD-A01", "FAST", "IDLE", "浦东新区充电站");
        addCharger("c0000000-0000-4000-8000-000000000007", "PD-B01", "SLOW", "IDLE", "浦东新区充电站");
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