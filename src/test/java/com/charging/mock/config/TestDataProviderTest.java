package com.charging.mock.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TestDataProvider}.
 *
 * <p>Validates that the local test data contains the expected set of chargers
 * with correct fields and data format matching what ApiClient expects.
 */
class TestDataProviderTest {

    @Test
    void getChargers_returnsNonEmptyList() {
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        assertNotNull(chargers);
        assertFalse(chargers.isEmpty());
    }

    @Test
    void getChargers_returnsAllSevenChargers() {
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        assertEquals(7, chargers.size());
    }

    @Test
    void getChargers_eachEntryHasRequiredFields() {
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        for (Map<String, Object> c : chargers) {
            assertNotNull(c.get("id"), "Charger should have an id");
            assertNotNull(c.get("chargerCode"), "Charger should have a chargerCode");
            assertNotNull(c.get("type"), "Charger should have a type");
            assertNotNull(c.get("status"), "Charger should have a status");
            assertNotNull(c.get("stationName"), "Charger should have a stationName");
        }
    }

    @Test
    void getChargers_chargerCodeFormat() {
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        for (Map<String, Object> c : chargers) {
            String code = (String) c.get("chargerCode");
            assertNotNull(code);
            assertTrue(code.matches("[A-Z]{2}-[AB]\\d{2}"),
                    "Charger code " + code + " should match pattern like CY-A01");
        }
    }

    @Test
    void getChargers_typeIsEitherFastOrSlow() {
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        for (Map<String, Object> c : chargers) {
            String type = (String) c.get("type");
            assertTrue("fast".equals(type) || "slow".equals(type),
                    "Charger type should be 'fast' or 'slow', got: " + type);
        }
    }

    @Test
    void getChargers_statusIsIdle() {
        // All test chargers should start as idle
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        for (Map<String, Object> c : chargers) {
            assertEquals("idle", c.get("status"));
        }
    }

    @Test
    void getChargerById_returnsCorrectCharger() {
        Map<String, Object> charger = TestDataProvider.getChargerById(
                "11111111-1111-1111-1111-111111111001");
        assertNotNull(charger);
        assertEquals("CY-A01", charger.get("chargerCode"));
        assertEquals("朝阳站A区1号", charger.get("stationName"));
    }

    @Test
    void getChargerById_returnsNullForUnknownId() {
        Map<String, Object> charger = TestDataProvider.getChargerById("nonexistent");
        assertNull(charger);
    }

    @Test
    void getChargerByCode_returnsCorrectCharger() {
        Map<String, Object> charger = TestDataProvider.getChargerByCode("HD-A01");
        assertNotNull(charger);
        assertEquals("22222222-2222-2222-2222-222222222001", charger.get("id"));
        assertEquals("海淀站A区1号", charger.get("stationName"));
    }

    @Test
    void getChargerByCode_returnsNullForUnknownCode() {
        Map<String, Object> charger = TestDataProvider.getChargerByCode("UNKNOWN");
        assertNull(charger);
    }

    @Test
    void getChargers_returnsDefensiveCopy() {
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        List<Map<String, Object>> chargers2 = TestDataProvider.getChargers();

        // Modifying the returned list should not affect the other reference
        chargers.clear();
        assertFalse(chargers2.isEmpty());
        assertEquals(7, chargers2.size());
    }

    @Test
    void getChargerById_withFirstCharger_hasAllExpectedFields() {
        Map<String, Object> charger = TestDataProvider.getChargerById(
                "11111111-1111-1111-1111-111111111001");
        assertNotNull(charger);
        assertEquals("CY-A01", charger.get("chargerCode"));
        assertEquals("fast", charger.get("type"));
        assertEquals("idle", charger.get("status"));
        assertEquals("朝阳站A区1号", charger.get("stationName"));
    }

    @Test
    void getChargerById_withSlowCharger_hasSlowType() {
        Map<String, Object> charger = TestDataProvider.getChargerById(
                "11111111-1111-1111-1111-111111111002");
        assertNotNull(charger);
        assertEquals("CY-A02", charger.get("chargerCode"));
        assertEquals("slow", charger.get("type"));
    }
}
