package com.charging.mock;

import com.charging.mock.util.QrCodeGenerator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ChargerUIPanel is a Swing panel that simulates the display and controls
 * of a physical charger.
 *
 * <p>Layout (top-to-bottom):
 * <ol>
 *   <li>Charger selector (JComboBox + refresh button) — unchanged</li>
 *   <li>Status label — simplified single line, shows current state</li>
 *   <li>Charging info display (energy, fee, duration, progress bar) — shown during charging</li>
 *   <li>Plug/Unplug buttons — side by side, the ONLY operational buttons</li>
 *   <li>Test scenario buttons — 3 buttons in a row: "断网测试", "服务器重启", "桩离线"</li>
 *   <li>QR code — auto-generated when charger is selected (before plug), displayed in a larger area</li>
 * </ol>
 *
 * <p>Events are delegated back to the parent {@link MockChargerClient} via
 * the simplified {@link ChargerUICallbacks} and {@link TestScenarioActions}
 * interfaces.
 */
public class ChargerUIPanel extends JPanel {

    private static final Color COLOR_IDLE = new Color(0x90, 0xEE, 0x90);    // light green
    private static final Color COLOR_PLUGGED = new Color(0xFF, 0xE4, 0xB5); // light yellow
    private static final Color COLOR_ERROR = Color.PINK;
    private static final Color COLOR_CHARGING = new Color(0x90, 0xEE, 0x90); // light green
    private static final int TEST_BUTTON_MIN_WIDTH = 140;
    private static final int TEST_BUTTON_HEIGHT = 38;

    // Charger selection
    final JComboBox<ChargerItem> chargerCombo;  // package-private for MockChargerClient access
    private final JButton refreshChargersBtn;

    // Multi-charger checkbox panel
    private final JPanel chargerCheckBoxPanel;
    private final List<JCheckBox> chargerCheckBoxes = new ArrayList<>();
    private final JButton applySelectedBtn;

    // Status
    private final JLabel statusLabel;

    // Charging info display
    private final JLabel energyLabel;
    private final JLabel feeLabel;
    private final JLabel durationLabel;
    private final JProgressBar chargeProgress;
    private final JPanel chargingInfoPanel;

    // Controls
    private final JButton plugButton;
    private final JButton unplugButton;

    // Test scenario buttons
    final JButton intermittentNetworkBtn;
    final JButton serverRestartBtn;
    final JButton chargerOfflineBtn;

    // QR
    private final JLabel qrCodeLabel;

    // State tracking
    private boolean pluggedIn;
    private String currentChargerId;
    private final Set<String> selectedChargerIds = new HashSet<>();

    /**
     * Construct the panel with a reference to the parent for event callbacks.
     *
     * @param callbacks   action listeners for charger operation buttons
     * @param testActions action listeners for test scenario buttons
     */
    public ChargerUIPanel(ChargerUICallbacks callbacks, TestScenarioActions testActions) {
        super(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(15, 15, 15, 15));
        setPreferredSize(new Dimension(480, 760));

        // ---- Top: Charger selector ----
        JPanel topPanel = new JPanel(new BorderLayout(5, 0));
        topPanel.setBorder(new TitledBorder("选择充电桩"));
        chargerCombo = new JComboBox<>();
        chargerCombo.addActionListener(e -> onChargerSelected());
        chargerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ChargerItem item) {
                    setText(item.displayText());
                }
                return this;
            }
        });
        refreshChargersBtn = new JButton("刷新");
        refreshChargersBtn.addActionListener(e -> callbacks.onRefreshChargers());
        topPanel.add(chargerCombo, BorderLayout.CENTER);
        topPanel.add(refreshChargersBtn, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // ---- Center: Status + Charging Info + Buttons ----
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBorder(new EmptyBorder(10, 0, 10, 0));

        // Status label (simplified single line)
        statusLabel = new JLabel("就绪 - 请选择充电桩并插枪");
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(10, 10, 10, 10)));
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        centerPanel.add(statusLabel);
        centerPanel.add(Box.createVerticalStrut(10));

        // Charging info display
        chargingInfoPanel = new JPanel();
        chargingInfoPanel.setLayout(new BoxLayout(chargingInfoPanel, BoxLayout.Y_AXIS));
        chargingInfoPanel.setBorder(new TitledBorder("充电信息"));
        chargingInfoPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        chargingInfoPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        energyLabel = new JLabel("当前电量: 0.0 kWh");
        energyLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        energyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        feeLabel = new JLabel("当前费用: 0.0 元");
        feeLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        feeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        durationLabel = new JLabel("充电时长: 0 分钟");
        durationLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        durationLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        chargeProgress = new JProgressBar(0, 100);
        chargeProgress.setStringPainted(true);
        chargeProgress.setIndeterminate(true);
        chargeProgress.setAlignmentX(Component.CENTER_ALIGNMENT);
        chargeProgress.setMaximumSize(new Dimension(400, 20));

        chargingInfoPanel.add(energyLabel);
        chargingInfoPanel.add(Box.createVerticalStrut(3));
        chargingInfoPanel.add(feeLabel);
        chargingInfoPanel.add(Box.createVerticalStrut(3));
        chargingInfoPanel.add(durationLabel);
        chargingInfoPanel.add(Box.createVerticalStrut(5));
        chargingInfoPanel.add(chargeProgress);

        // Initially hidden until charging starts
        chargingInfoPanel.setVisible(false);
        centerPanel.add(chargingInfoPanel);
        centerPanel.add(Box.createVerticalStrut(10));

        // Plug/Unplug buttons — side by side, the ONLY operational buttons
        JPanel plugPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));

        plugButton = new JButton("插枪");
        plugButton.setPreferredSize(new Dimension(120, 40));
        plugButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        plugButton.setEnabled(false);
        plugButton.addActionListener(e -> {
            if (callbacks.onPlugIn(currentChargerId)) {
                setPluggedIn(true);
                setStatusText("已插枪 — 请使用Flutter App扫描二维码启动充电", COLOR_PLUGGED);
            }
        });

        unplugButton = new JButton("拔枪");
        unplugButton.setPreferredSize(new Dimension(120, 40));
        unplugButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        unplugButton.setEnabled(false);
        unplugButton.addActionListener(e -> {
            if (callbacks.onUnplug()) {
                setPluggedIn(false);
                setStatusText("已拔枪 - 充电桩已释放", COLOR_IDLE);
            }
        });

        plugPanel.add(plugButton);
        plugPanel.add(unplugButton);
        centerPanel.add(plugPanel);
        centerPanel.add(Box.createVerticalStrut(10));

        // ---- 多桩勾选面板 ----
        chargerCheckBoxPanel = new JPanel();
        chargerCheckBoxPanel.setLayout(new BoxLayout(chargerCheckBoxPanel, BoxLayout.Y_AXIS));
        chargerCheckBoxPanel.setBorder(new TitledBorder("勾选要模拟的充电桩"));
        JScrollPane checkScroll = new JScrollPane(chargerCheckBoxPanel);
        checkScroll.setPreferredSize(new Dimension(450, 100));
        applySelectedBtn = new JButton("应用选中");
        applySelectedBtn.addActionListener(e -> callbacks.onApplySelected(getSelectedChargerIds()));
        JPanel applyPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        applyPanel.add(applySelectedBtn);
        JPanel multiPanel = new JPanel(new BorderLayout());
        multiPanel.add(checkScroll, BorderLayout.CENTER);
        multiPanel.add(applyPanel, BorderLayout.SOUTH);
        centerPanel.add(multiPanel);
        centerPanel.add(Box.createVerticalStrut(10));

        // Test scenario buttons — 3 in a row, with adequate size
        JPanel testPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));

        intermittentNetworkBtn = new JButton("断网测试");
        intermittentNetworkBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        intermittentNetworkBtn.setPreferredSize(new Dimension(TEST_BUTTON_MIN_WIDTH, TEST_BUTTON_HEIGHT));
        intermittentNetworkBtn.addActionListener(e -> testActions.onIntermittentNetwork());

        serverRestartBtn = new JButton("服务器重启");
        serverRestartBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        serverRestartBtn.setPreferredSize(new Dimension(TEST_BUTTON_MIN_WIDTH, TEST_BUTTON_HEIGHT));
        serverRestartBtn.addActionListener(e -> testActions.onServerRestart());

        chargerOfflineBtn = new JButton("桩离线");
        chargerOfflineBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        chargerOfflineBtn.setPreferredSize(new Dimension(TEST_BUTTON_MIN_WIDTH, TEST_BUTTON_HEIGHT));
        chargerOfflineBtn.addActionListener(e -> testActions.onChargerOffline());

        testPanel.add(intermittentNetworkBtn);
        testPanel.add(serverRestartBtn);
        testPanel.add(chargerOfflineBtn);
        centerPanel.add(testPanel);

        add(centerPanel, BorderLayout.CENTER);

        // ---- Bottom: QR code (larger area) ----
        JPanel qrPanel = new JPanel(new BorderLayout());
        qrPanel.setBorder(new TitledBorder("充电桩二维码 — 扫码启动充电"));
        qrCodeLabel = new JLabel();
        qrCodeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrCodeLabel.setPreferredSize(new Dimension(300, 300));
        qrCodeLabel.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        qrCodeLabel.setText("（选择充电桩后自动生成）");
        qrPanel.add(qrCodeLabel, BorderLayout.CENTER);
        add(qrPanel, BorderLayout.SOUTH);

        // Initial state
        setPluggedIn(false);
        setStatusText("就绪 - 请选择充电桩并插枪", COLOR_IDLE);
    }

    // ===== Public API =====

    /**
     * Populate the charger combo and checkbox list with items fetched from the backend.
     */
    public void setChargerList(List<Map<String, Object>> chargers) {
        // Reset combo box
        chargerCombo.removeAllItems();
        chargerCombo.addItem(new ChargerItem(null, "-- 请选择充电桩 --", ""));
        for (Map<String, Object> c : chargers) {
            String id = String.valueOf(c.get("id"));
            String code = (String) c.getOrDefault("chargerCode", id);
            String status = (String) c.getOrDefault("status", "UNKNOWN");
            chargerCombo.addItem(new ChargerItem(id, code, status));
        }
        if (!chargers.isEmpty()) {
            chargerCombo.setSelectedIndex(1);
            onChargerSelected();
        }

        // Reset checkbox panel
        chargerCheckBoxPanel.removeAll();
        chargerCheckBoxes.clear();
        for (Map<String, Object> c : chargers) {
            String id = String.valueOf(c.get("id"));
            String code = (String) c.getOrDefault("chargerCode", id);
            String status = (String) c.getOrDefault("status", "UNKNOWN");
            JCheckBox cb = new JCheckBox(code + "  [" + status + "]");
            cb.putClientProperty("chargerId", id);
            chargerCheckBoxes.add(cb);
            chargerCheckBoxPanel.add(cb);
        }
        chargerCheckBoxPanel.revalidate();
        chargerCheckBoxPanel.repaint();
    }

    /**
     * 获取勾选中的充电桩 ID 列表。
     */
    public List<String> getSelectedChargerIds() {
        List<String> ids = new ArrayList<>();
        for (JCheckBox cb : chargerCheckBoxes) {
            if (cb.isSelected()) {
                String id = (String) cb.getClientProperty("chargerId");
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Show an error message on the panel.
     */
    public void showError(String title, String message) {
        setStatusText("错误: " + message, COLOR_ERROR);
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Reset the panel to its idle state (after unplug).
     * QR code and charger selection are preserved — only plugged state is cleared.
     */
    public void resetToIdle() {
        this.pluggedIn = false;

        // Preserve QR and charger selection — charger hasn't changed
        chargerCombo.setEnabled(true);
        refreshChargersBtn.setEnabled(true);
        plugButton.setEnabled(currentChargerId != null);
        unplugButton.setEnabled(false);

        // Hide charging info
        chargingInfoPanel.setVisible(false);

        setStatusText("已拔枪 - 充电桩已释放", COLOR_IDLE);
    }

    /**
     * Update the charging info display with current simulation data.
     * Called every second during charging.
     *
     * @param energyKwh     current energy in kWh
     * @param fee           current accumulated fee
     * @param elapsedSeconds elapsed time in seconds
     */
    public void updateChargingUI(double energyKwh, BigDecimal fee, long elapsedSeconds) {
        energyLabel.setText(String.format("当前电量: %.2f kWh", energyKwh));
        feeLabel.setText("当前费用: " + fee + " 元");
        durationLabel.setText("充电时长: " + (elapsedSeconds / 60) + " 分钟 " + (elapsedSeconds % 60) + " 秒");

        // Update progress bar (capped at 100, where 100 = 50 kWh max)
        int progress = Math.min(100, (int) (energyKwh / 50.0 * 100));
        chargeProgress.setIndeterminate(false);
        chargeProgress.setValue(progress);
        chargeProgress.setString(progress + "%");
    }

    /**
     * Show the final charging result after charging stops.
     *
     * @param energy final energy in kWh
     * @param fee    final fee charged
     */
    public void showChargeResult(BigDecimal energy, BigDecimal fee) {
        energyLabel.setText(String.format("总电量: %s kWh", energy));
        feeLabel.setText("总费用: " + fee + " 元");

        // Show full progress after charge completes
        int progress = Math.min(100, (int) (energy.doubleValue() / 50.0 * 100));
        chargeProgress.setIndeterminate(false);
        chargeProgress.setValue(progress);
        chargeProgress.setString("完成 (" + progress + "%)");
    }

    // ===== Internal =====

    /**
     * Set the plugged-in state, enabling/disabling applicable buttons.
     * Made public so {@link MockChargerClient} can update state on HTTP notification.
     */
    public void setPluggedIn(boolean plugged) {
        this.pluggedIn = plugged;
        plugButton.setEnabled(!plugged && currentChargerId != null);
        // Requirement: unplug should ALWAYS be available if a charger is selected
        // to allow for force-releasing occupied chargers.
        unplugButton.setEnabled(currentChargerId != null);
    }

    public void setStatusText(String text, Color bg) {
        statusLabel.setText(text);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(bg);
    }

    private void onChargerSelected() {
        ChargerItem item = (ChargerItem) chargerCombo.getSelectedItem();
        if (item != null && item.id != null) {
            currentChargerId = item.id;
            plugButton.setEnabled(!pluggedIn);
            unplugButton.setEnabled(true); // Enable on selection
            // Auto-generate QR when charger is selected (before plug, no sessionId yet)
            generateQrForCharger(item.id, null);
        } else {
            currentChargerId = null;
            plugButton.setEnabled(false);
            unplugButton.setEnabled(false); // Disable if none selected
            qrCodeLabel.setIcon(null);
            qrCodeLabel.setText("（请选择充电桩）");
        }
    }

    /**
     * Generate a QR code containing charger identity AND sessionId for Flutter to scan.
     * Flutter scans this QR, calls selectCharger with sessionId, then starts charge.
     */
    public void generateQrForCharger(String chargerId, String sessionId) {
        String qrData = String.format(
                "{\"chargerId\":\"%s\",\"sessionId\":\"%s\",\"stationName\":\"%s\"}",
                chargerId,
                sessionId != null ? sessionId : "",
                chargerCombo.getSelectedItem() != null
                        ? chargerCombo.getSelectedItem().toString()
                        : "unknown"
        );

        BufferedImage qrImage = QrCodeGenerator.generateQR(qrData, 280, 280);
        if (qrImage != null) {
            qrCodeLabel.setIcon(new ImageIcon(qrImage));
            qrCodeLabel.setText("");
        } else {
            qrCodeLabel.setText("QR生成失败");
        }
    }

    // ===== Accessors =====

    public boolean isPluggedIn() {
        return pluggedIn;
    }

    // ===== ChargerItem inner class =====

    /**
     * Combo box item representing a charger with its display text.
     */
    public static class ChargerItem {
        final String id;
        final String code;
        final String status;

        ChargerItem(String id, String code, String status) {
            this.id = id;
            this.code = code;
            this.status = status;
        }

        String displayText() {
            if (id == null) return code;
            return code + "  [" + status + "]";
        }

        @Override
        public String toString() {
            return displayText();
        }
    }

    // ===== Callback interfaces =====

    /**
     * Callbacks that the panel invokes when operation buttons are pressed.
     * Implemented by {@link MockChargerClient}.
     */
    public interface ChargerUICallbacks {
        void onRefreshChargers();
        boolean onPlugIn(String chargerId);
        boolean onUnplug();
        void onApplySelected(List<String> selectedChargerIds);
    }

    /**
     * Callbacks for test scenario buttons.
     * Implemented by {@link MockChargerClient}.
     */
    public interface TestScenarioActions {
        void onIntermittentNetwork();
        void onServerRestart();
        void onChargerOffline();
    }
}
