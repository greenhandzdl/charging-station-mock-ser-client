package com.charging.mock;

import com.charging.mock.model.ChargeRecord;
import com.charging.mock.service.ChargeSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChargerUIPanel}.
 * <p>
 * These tests validate:
 * <ul>
 *   <li>Panel construction -- component creation and initial state</li>
 *   <li>Charger list population -- combo box items and selection</li>
 *   <li>Simulation callbacks -- onChargeStarted state transitions</li>
 *   <li>UI refresh from simulator -- energy, time labels, progress bar</li>
 *   <li>Reset to idle -- all fields restored to default</li>
 *   <li>ChargerItem rendering -- display text formatting</li>
 *   <li>Edge cases -- empty list, null charger data, null simulator</li>
 * </ul>
 * <p>
 * Tests that would trigger JOptionPane dialogs (onChargeStopped, showError) are
 * avoided in headless environments. They instead verify the underlying state
 * transitions directly.
 */
class ChargerUIPanelTest {

    private ChargerUIPanel panel;
    private TestCallbacks callbacks;

    @BeforeEach
    void setUp() {
        callbacks = new TestCallbacks();
        panel = new ChargerUIPanel(callbacks);
    }

    // ===== Construction =====

    @Test
    void constructor_createsComboBox() {
        assertNotNull(panel.chargerCombo);
        // Initially empty -- placeholder added only in setChargerList
        assertEquals(0, panel.chargerCombo.getItemCount());
    }

    @Test
    void constructor_initialStateTextAndFlags() {
        assertEquals("就绪 - 请选择充电桩并插枪", getStatusText());
        assertFalse(panel.isCharging());
        assertNull(panel.getCurrentRecordId());
    }

    @Test
    void constructor_initialButtonStates() {
        // After construction: not plugged, no charger selected.
        // plugButton is enabled because setPluggedIn(false) sets it to !plugged = true.
        // The plug button should be enabled because the user could plug in after selecting a charger.
        // But initially no charger is selected, so plugButton is disabled by onChargerSelected which
        // runs when combo selection changes. However, with no items, selection doesn't fire.
        JButton plugButton = findButton("插枪");
        JButton unplugButton = findButton("拔枪");
        JButton startButton = findButton("启动充电");
        JButton stopButton = findButton("停止充电");

        assertNotNull(plugButton);
        assertNotNull(unplugButton);
        assertNotNull(startButton);
        assertNotNull(stopButton);

        // unplug, start, stop are disabled initially
        assertFalse(unplugButton.isEnabled());
        assertFalse(startButton.isEnabled());
        assertFalse(stopButton.isEnabled());
    }

    // ===== Set charger list =====

    @Test
    void setChargerList_populatesComboBox() {
        List<Map<String, Object>> chargers = createChargerList(
                "c1", "CHARGER-A", "IDLE",
                "c2", "CHARGER-B", "CHARGING");

        panel.setChargerList(chargers);

        assertEquals(3, panel.chargerCombo.getItemCount()); // placeholder + 2
        assertEquals("-- 请选择充电桩 --", panel.chargerCombo.getItemAt(0).displayText());
        assertEquals("CHARGER-A  [IDLE]", panel.chargerCombo.getItemAt(1).displayText());
        assertEquals("CHARGER-B  [CHARGING]", panel.chargerCombo.getItemAt(2).displayText());
    }

    @Test
    void setChargerList_emptyList_containsOnlyPlaceholder() {
        panel.setChargerList(List.of());

        assertEquals(1, panel.chargerCombo.getItemCount());
        assertEquals("-- 请选择充电桩 --", panel.chargerCombo.getItemAt(0).displayText());
    }

    @Test
    void setChargerList_autoSelectsFirstCharger() {
        panel.setChargerList(createChargerList("c1", "CHARGER-X", "IDLE"));

        assertEquals(1, panel.chargerCombo.getSelectedIndex());
        ChargerUIPanel.ChargerItem selected =
                (ChargerUIPanel.ChargerItem) panel.chargerCombo.getSelectedItem();
        assertNotNull(selected);
        assertEquals("c1", selected.id);
    }

    @Test
    void setChargerList_missingChargerCode_fallsBackToId() {
        List<Map<String, Object>> chargers = new ArrayList<>();
        Map<String, Object> c = new HashMap<>();
        c.put("id", "c1");
        c.put("status", "FAULT");
        chargers.add(c);

        panel.setChargerList(chargers);

        ChargerUIPanel.ChargerItem item = panel.chargerCombo.getItemAt(1);
        assertEquals("c1", item.code);
        assertEquals("c1  [FAULT]", item.displayText());
    }

    // ===== Charge started =====

    @Test
    void onChargeStarted_setsChargingStateAndDisablesStartButton() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));

        ChargeRecord record = createRecord("rec-001", "c1");
        ChargeSimulator sim = new ChargeSimulator();
        sim.startSimulation();

        panel.onChargeStarted(record, sim);

        assertTrue(panel.isCharging());
        assertEquals("rec-001", panel.getCurrentRecordId());

        JButton startButton = findButton("启动充电");
        JButton stopButton = findButton("停止充电");
        assertFalse(startButton.isEnabled());
        assertTrue(stopButton.isEnabled());
    }

    @Test
    void onChargeStarted_showsChargingStatusWithAbbreviatedId() {
        ChargeRecord record = createRecord("a1b2c3d4-e5f6-7890-1234-567890abcdef", "c1");
        ChargeSimulator sim = new ChargeSimulator();
        sim.startSimulation();

        panel.onChargeStarted(record, sim);

        String status = getStatusText();
        assertTrue(status.contains("充电中"));
        assertTrue(status.contains("a1b2c3d4")); // first 8 chars of UUID
    }

    // ===== Refresh from simulator =====

    @Test
    void refreshFromSimulator_updatesEnergyLabel() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        ChargeSimulator sim = new ChargeSimulator();
        sim.startSimulation();
        panel.onChargeStarted(createRecord("r1", "c1"), sim);

        // Accumulate 5 ticks = 0.5 kWh
        for (int i = 0; i < 5; i++) {
            sim.tick();
        }

        panel.refreshFromSimulator(sim);

        String energyText = findLabelText("电量:");
        assertNotNull(energyText);
        assertTrue(energyText.contains("0.50"));
    }

    @Test
    void refreshFromSimulator_withNullSimulator_doesNotThrow() {
        panel.refreshFromSimulator(null);
        // no-op, should not throw
    }

    @Test
    void refreshFromSimulator_whenNotCharging_doesNotThrow() {
        ChargeSimulator sim = new ChargeSimulator();
        // sim not started
        panel.refreshFromSimulator(sim);
        // no-op, should not throw
    }

    @Test
    void refreshFromSimulator_updatesProgressBar() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        ChargeSimulator sim = new ChargeSimulator();
        sim.startSimulation();
        panel.onChargeStarted(createRecord("r1", "c1"), sim);

        // 10 ticks = 1.0 kWh => 2% progress
        for (int i = 0; i < 10; i++) {
            sim.tick();
        }

        panel.refreshFromSimulator(sim);

        // Progress bar value should reflect (energy / 50.0) * 100
        assertTrue(getProgressBarValue() > 0);
    }

    // ===== Reset to idle =====

    @Test
    void resetToIdle_restoresInitialFields() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        panel.onChargeStarted(createRecord("r1", "c1"), new ChargeSimulator());
        panel.resetToIdle();

        assertFalse(panel.isCharging());
        assertNull(panel.getCurrentRecordId());
        assertTrue(panel.chargerCombo.isEnabled());
    }

    @Test
    void resetToIdle_resetsEnergyLabels() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        panel.onChargeStarted(createRecord("r1", "c1"), new ChargeSimulator());

        // Change labels via refresh
        ChargeSimulator sim = new ChargeSimulator();
        sim.startSimulation();
        for (int i = 0; i < 5; i++) {
            sim.tick();
        }
        panel.refreshFromSimulator(sim);

        panel.resetToIdle();

        // Labels should be back to zeros
        String energyText = findLabelText("电量:");
        assertEquals("电量: 0.00 kWh", energyText);
        String timeText = findLabelText("时长:");
        assertEquals("时长: 0 min", timeText);
    }

    @Test
    void resetToIdle_resetsButtonStates() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        panel.onChargeStarted(createRecord("r1", "c1"), new ChargeSimulator());
        panel.resetToIdle();

        JButton plugButton = findButton("插枪");
        JButton unplugButton = findButton("拔枪");
        JButton startButton = findButton("启动充电");
        JButton stopButton = findButton("停止充电");

        // After resetToIdle: plug = true, unplug = false, start = false, stop = false
        assertFalse(unplugButton.isEnabled());
        assertFalse(startButton.isEnabled());
        assertFalse(stopButton.isEnabled());
    }

    // ===== ChargerItem =====

    @Test
    void chargerItem_displayWithValidId_showsCodeAndStatus() {
        ChargerUIPanel.ChargerItem item =
                new ChargerUIPanel.ChargerItem("id-1", "C001", "IDLE");
        assertEquals("C001  [IDLE]", item.displayText());
    }

    @Test
    void chargerItem_displayWithNullId_returnsCodeOnly() {
        ChargerUIPanel.ChargerItem item =
                new ChargerUIPanel.ChargerItem(null, "-- 请选择 --", "");
        assertEquals("-- 请选择 --", item.displayText());
    }

    @Test
    void chargerItem_toString_matchesDisplayText() {
        ChargerUIPanel.ChargerItem item =
                new ChargerUIPanel.ChargerItem("id-1", "C001", "IDLE");
        assertEquals(item.displayText(), item.toString());
    }

    @Test
    void chargerItem_displayWithFaultStatus() {
        ChargerUIPanel.ChargerItem item =
                new ChargerUIPanel.ChargerItem("id-3", "C003", "FAULT");
        assertEquals("C003  [FAULT]", item.displayText());
    }

    // ===== Set status text =====

    @Test
    void setStatusText_updatesLabelContent() {
        panel.setStatusText("自定义状态", java.awt.Color.RED);
        assertEquals("自定义状态", getStatusText());
    }

    // ===== Test helpers =====

    private String getStatusText() {
        try {
            Field field = ChargerUIPanel.class.getDeclaredField("statusLabel");
            field.setAccessible(true);
            JLabel label = (JLabel) field.get(panel);
            return label.getText();
        } catch (Exception e) {
            throw new RuntimeException("Could not access statusLabel", e);
        }
    }

    private int getProgressBarValue() {
        try {
            Field field = ChargerUIPanel.class.getDeclaredField("progressBar");
            field.setAccessible(true);
            JProgressBar bar = (JProgressBar) field.get(panel);
            return bar.getValue();
        } catch (Exception e) {
            throw new RuntimeException("Could not access progressBar", e);
        }
    }

    private JButton findButton(String text) {
        return findButtonRecursive(panel, text);
    }

    private JButton findButtonRecursive(java.awt.Container container, String text) {
        for (java.awt.Component comp : container.getComponents()) {
            if (comp instanceof JButton btn && text.equals(btn.getText())) {
                return btn;
            }
            if (comp instanceof java.awt.Container c) {
                JButton found = findButtonRecursive(c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String findLabelText(String prefix) {
        return findLabelTextRecursive(panel, prefix);
    }

    private String findLabelTextRecursive(java.awt.Container container, String prefix) {
        for (java.awt.Component comp : container.getComponents()) {
            if (comp instanceof JLabel label && label.getText().startsWith(prefix)) {
                return label.getText();
            }
            if (comp instanceof java.awt.Container c) {
                String found = findLabelTextRecursive(c, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> createChargerList(String... idCodeStatusTriples) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < idCodeStatusTriples.length; i += 3) {
            Map<String, Object> c = new HashMap<>();
            c.put("id", idCodeStatusTriples[i]);
            c.put("chargerCode", idCodeStatusTriples[i + 1]);
            c.put("status", idCodeStatusTriples[i + 2]);
            list.add(c);
        }
        return list;
    }

    private static ChargeRecord createRecord(String id, String chargerId) {
        ChargeRecord r = new ChargeRecord();
        r.setId(id);
        r.setChargerId(chargerId);
        r.setStatus("CHARGING");
        return r;
    }

    // ===== Test callbacks =====

    static class TestCallbacks implements ChargerUIPanel.ChargerUICallbacks {
        boolean refreshChargersCalled;
        boolean plugInCalled;
        boolean unplugCalled;
        boolean startChargeCalled;
        boolean stopChargeCalled;
        String lastChargerId;
        boolean plugInResult = true;
        boolean unplugResult = true;

        @Override
        public void onRefreshChargers() {
            refreshChargersCalled = true;
        }

        @Override
        public boolean onPlugIn(String chargerId) {
            plugInCalled = true;
            lastChargerId = chargerId;
            return plugInResult;
        }

        @Override
        public boolean onUnplug() {
            unplugCalled = true;
            return unplugResult;
        }

        @Override
        public void onStartCharge(String chargerId) {
            startChargeCalled = true;
            lastChargerId = chargerId;
        }

        @Override
        public void onStopCharge() {
            stopChargeCalled = true;
        }
    }
}