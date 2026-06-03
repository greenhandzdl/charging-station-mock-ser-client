package com.charging.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChargerUIPanel} (simplified layout).
 *
 * <p>Tests validate the simplified panel with only:
 * charger selector → status label → plug/unplug buttons → test scenario buttons → QR code.
 */
class ChargerUIPanelTest {

    private ChargerUIPanel panel;
    private TestCallbacks callbacks;
    private TestScenarioActionsImpl testActions;

    @BeforeEach
    void setUp() {
        callbacks = new TestCallbacks();
        testActions = new TestScenarioActionsImpl();
        panel = new ChargerUIPanel(callbacks, testActions);
    }

    // ===== Construction =====

    @Test
    void constructor_createsComboBox() {
        assertNotNull(panel.chargerCombo);
        assertEquals(0, panel.chargerCombo.getItemCount());
    }

    @Test
    void constructor_initialStateText() {
        assertEquals("就绪 - 请选择充电桩并插枪", getStatusText());
        assertFalse(panel.isPluggedIn());
    }

    @Test
    void constructor_initialButtonStates() {
        JButton plugButton = findButton("插枪");
        JButton unplugButton = findButton("拔枪");

        assertNotNull(plugButton);
        assertNotNull(unplugButton);

        // No charger selected initially, so plug is disabled, unplug is disabled
        assertFalse(plugButton.isEnabled());
        assertFalse(unplugButton.isEnabled());
    }

    @Test
    void constructor_testScenarioButtonsExist() {
        assertNotNull(findButton("断网测试"));
        assertNotNull(findButton("服务器重启"));
        assertNotNull(findButton("桩离线"));
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

    @Test
    void setChargerList_generatesQrOnSelection() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        // QR label should not show placeholder text after selection
        assertNotEquals("（选择充电桩后自动生成）", getQrLabelText());
    }

    // ===== Plug/Unplug =====

    @Test
    void plugIn_enablesUnplugButton() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));

        // Click the actual plug button on the panel
        JButton plugBtn = findButton("插枪");
        assertNotNull(plugBtn);
        assertTrue(plugBtn.isEnabled()); // enabled because charger selected, not plugged
        plugBtn.doClick();
        assertTrue(callbacks.plugInCalled);
        assertEquals("c1", callbacks.lastChargerId);

        JButton unplugBtn = findButton("拔枪");
        assertTrue(unplugBtn.isEnabled());
        assertFalse(plugBtn.isEnabled());
    }

    @Test
    void unplug_resetsToIdle() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));

        // Plug in via button click
        findButton("插枪").doClick();
        assertTrue(callbacks.plugInCalled);

        // Unplug via button click
        findButton("拔枪").doClick();
        assertTrue(callbacks.unplugCalled);
        assertFalse(panel.isPluggedIn());
    }

    // ===== Test scenario buttons =====

    @Test
    void intermittentNetworkButton_triggersCallback() {
        JButton btn = findButton("断网测试");
        assertNotNull(btn);
        btn.doClick();
        assertTrue(testActions.intermittentCalled);
    }

    @Test
    void serverRestartButton_triggersCallback() {
        JButton btn = findButton("服务器重启");
        assertNotNull(btn);
        btn.doClick();
        assertTrue(testActions.serverRestartCalled);
    }

    @Test
    void chargerOfflineButton_triggersCallback() {
        JButton btn = findButton("桩离线");
        assertNotNull(btn);
        btn.doClick();
        assertTrue(testActions.chargerOfflineCalled);
    }

    // ===== Reset to idle =====

    @Test
    void resetToIdle_restoresInitialFields() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        panel.resetToIdle();

        assertFalse(panel.isPluggedIn());
        assertTrue(panel.chargerCombo.isEnabled());
        assertEquals("（选择充电桩后自动生成）", getQrLabelText());
    }

    @Test
    void resetToIdle_resetsButtonStates() {
        panel.setChargerList(createChargerList("c1", "C1", "IDLE"));
        panel.resetToIdle();

        JButton plugButton = findButton("插枪");
        JButton unplugButton = findButton("拔枪");

        assertFalse(plugButton.isEnabled());
        assertFalse(unplugButton.isEnabled());
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

    private String getQrLabelText() {
        try {
            Field field = ChargerUIPanel.class.getDeclaredField("qrCodeLabel");
            field.setAccessible(true);
            JLabel label = (JLabel) field.get(panel);
            return label.getText();
        } catch (Exception e) {
            throw new RuntimeException("Could not access qrCodeLabel", e);
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

    // ===== Test callbacks =====

    static class TestCallbacks implements ChargerUIPanel.ChargerUICallbacks {
        boolean refreshChargersCalled;
        boolean plugInCalled;
        boolean unplugCalled;
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
    }

    static class TestScenarioActionsImpl implements ChargerUIPanel.TestScenarioActions {
        boolean intermittentCalled;
        boolean serverRestartCalled;
        boolean chargerOfflineCalled;

        @Override
        public void onIntermittentNetwork() {
            intermittentCalled = true;
        }

        @Override
        public void onServerRestart() {
            serverRestartCalled = true;
        }

        @Override
        public void onChargerOffline() {
            chargerOfflineCalled = true;
        }
    }
}