package com.charging.mock;

import com.charging.mock.model.ChargeRecord;
import com.charging.mock.service.ChargeSimulator;
import com.charging.mock.util.QrCodeGenerator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ChargerUIPanel is a Swing panel that simulates the display and controls
 * of a physical charger.
 *
 * <p>Layout (top-to-bottom):
 * <ol>
 *   <li>Charger selector (JComboBox) — populated from backend API</li>
 *   <li>Status display — current state, power, energy labels</li>
 *   <li>Progress bar — visual energy accumulation</li>
 *   <li>Control buttons — Plug In / Unplug, Start Charge / Stop Charge</li>
 *   <li>QR code display area — generated from session data for Flutter client to scan</li>
 * </ol>
 *
 * <p>Events are delegated back to the parent {@link MockChargerClient} via
 * {@link ActionListener} callbacks, keeping the panel reusable.
 */
public class ChargerUIPanel extends JPanel {

    private static final Color COLOR_IDLE = new Color(0x90, 0xEE, 0x90);    // light green
    private static final Color COLOR_PLUGGED = new Color(0xFF, 0xE4, 0xB5); // light yellow
    private static final Color COLOR_CHARGING = new Color(0xB0, 0xC4, 0xDE); // light steel blue
    private static final Color COLOR_DONE = new Color(0xE6, 0xE6, 0xFA);    // lavender

    // Charger selection
    final JComboBox<ChargerItem> chargerCombo;  // package-private for MockChargerClient access
    private final JButton refreshChargersBtn;

    // Status
    private final JLabel statusLabel;
    private final JLabel powerLabel;
    private final JLabel energyLabel;
    private final JLabel timeLabel;

    // Progress
    private final JProgressBar progressBar;

    // Controls
    private final JButton plugButton;
    private final JButton unplugButton;
    private final JButton startButton;
    private final JButton stopButton;

    // QR
    private final JLabel qrCodeLabel;

    // State tracking
    private boolean pluggedIn;
    private boolean charging;
    private String currentChargerId;
    private String currentRecordId;
    private ChargeRecord lastRecord;
    private ChargeSimulator simulator;

    /**
     * Construct the panel with a reference to the parent for event callbacks.
     *
     * @param callbacks action listeners for button events
     */
    public ChargerUIPanel(ChargerUICallbacks callbacks) {
        super(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(15, 15, 15, 15));
        setPreferredSize(new Dimension(480, 620));

        // ---- Top: Charger selector ----
        JPanel topPanel = new JPanel(new BorderLayout(5, 0));
        topPanel.setBorder(new TitledBorder("选择充电桩"));
        chargerCombo = new JComboBox<>();
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

        // ---- Center: Status + Progress ----
        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setBorder(new TitledBorder("充电状态"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 8, 4, 8);

        // Status label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        statusLabel = new JLabel("就绪 - 请选择充电桩并插枪");
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(10, 10, 10, 10)));
        centerPanel.add(statusLabel, gbc);

        // Energy label
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.weightx = 0.5;
        energyLabel = new JLabel("电量: 0.00 kWh");
        energyLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        centerPanel.add(energyLabel, gbc);

        // Power label
        gbc.gridx = 1;
        powerLabel = new JLabel("功率: 0.0 kW");
        powerLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        centerPanel.add(powerLabel, gbc);

        // Time label
        gbc.gridx = 0;
        gbc.gridy = 2;
        timeLabel = new JLabel("时长: 0 min");
        timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        centerPanel.add(timeLabel, gbc);

        // Progress bar
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setFont(new Font("SansSerif", Font.PLAIN, 12));
        progressBar.setValue(0);
        progressBar.setString("0.0 / 50.0 kWh");
        centerPanel.add(progressBar, gbc);

        add(centerPanel, BorderLayout.CENTER);

        // ---- Bottom: Controls + QR ----
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));

        // Buttons
        JPanel buttonPanel = new JPanel(new GridLayout(2, 2, 8, 8));

        plugButton = new JButton("插枪");
        plugButton.setEnabled(false);
        plugButton.addActionListener(e -> {
            if (callbacks.onPlugIn(currentChargerId)) {
                setPluggedIn(true);
                setStatusText("已插枪，请点击\"启动充电\"", COLOR_PLUGGED);
            }
        });

        unplugButton = new JButton("拔枪");
        unplugButton.setEnabled(false);
        unplugButton.addActionListener(e -> {
            if (charging) {
                JOptionPane.showMessageDialog(this,
                        "充电进行中，请先停止充电再拔枪",
                        "操作提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (callbacks.onUnplug()) {
                setPluggedIn(false);
                setStatusText("已拔枪 - 充电桩已释放", COLOR_IDLE);
            }
        });

        startButton = new JButton("启动充电");
        startButton.setEnabled(false);
        startButton.addActionListener(e -> {
            if (currentChargerId == null) {
                JOptionPane.showMessageDialog(this, "请先选择充电桩",
                        "操作提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            callbacks.onStartCharge(currentChargerId);
        });

        stopButton = new JButton("停止充电");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> callbacks.onStopCharge());

        buttonPanel.add(plugButton);
        buttonPanel.add(unplugButton);
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);

        // QR code
        JPanel qrPanel = new JPanel(new BorderLayout());
        qrPanel.setBorder(new TitledBorder("充电会话 QR Code"));
        qrCodeLabel = new JLabel();
        qrCodeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrCodeLabel.setPreferredSize(new Dimension(210, 210));
        qrCodeLabel.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        qrCodeLabel.setText("（等待充电启动后生成）");
        qrPanel.add(qrCodeLabel, BorderLayout.CENTER);
        bottomPanel.add(qrPanel, BorderLayout.CENTER);

        add(bottomPanel, BorderLayout.SOUTH);

        // Initial state
        setPluggedIn(false);
        setStatusText("就绪 - 请选择充电桩并插枪", COLOR_IDLE);
    }

    // ===== Public API =====

    /**
     * Populate the charger combo box with items fetched from the backend.
     */
    public void setChargerList(List<Map<String, Object>> chargers) {
        chargerCombo.removeAllItems();
        // Add a placeholder
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
    }

    /**
     * Start a charging session: update panel state and begin simulation display.
     */
    public void onChargeStarted(ChargeRecord record, ChargeSimulator sim) {
        this.lastRecord = record;
        this.currentRecordId = record.getId();
        this.charging = true;
        this.simulator = sim;

        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        plugButton.setEnabled(false);
        unplugButton.setEnabled(false);
        chargerCombo.setEnabled(false);
        refreshChargersBtn.setEnabled(false);

        setStatusText("充电中... 记录 #" + abbreviateId(record.getId()), COLOR_CHARGING);
        generateQrForRecord(record);

        // Update the time label with start time
        timeLabel.setText("时长: 0 min  (开始: " + (sim.getStartTime() != null
                ? sim.getStartTime().toLocalTime().toString().substring(0, 8)
                : "--") + ")");
    }

    /**
     * Stop a charging session: update panel state and show results.
     */
    public void onChargeStopped(ChargeRecord record, ChargeSimulator.SimulationResult result) {
        this.lastRecord = record;
        this.charging = false;

        stopButton.setEnabled(false);
        startButton.setEnabled(true);

        setStatusText("充电完成", COLOR_DONE);

        // Show dialog with result
        String msg = String.format(
                "充电完成!\n\n充电时长: %d 分 %d 秒\n充电电量: %.2f kWh\n充电费用: %.2f 元\n扣费状态: %s",
                result.getDurationSeconds() / 60,
                result.getDurationSeconds() % 60,
                result.getEnergyKwh(),
                result.getFee(),
                record.getDeductionStatus() != null ? record.getDeductionStatus() : "PAID");

        JOptionPane.showMessageDialog(this, msg, "充电结束", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Show an error message on the panel.
     */
    public void showError(String title, String message) {
        setStatusText("错误: " + message, Color.PINK);
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Reset the panel to its initial idle state (after unplug).
     */
    public void resetToIdle() {
        this.charging = false;
        this.pluggedIn = false;
        this.currentChargerId = null;
        this.currentRecordId = null;
        this.lastRecord = null;
        this.simulator = null;

        progressBar.setValue(0);
        progressBar.setString("0.0 / 50.0 kWh");
        energyLabel.setText("电量: 0.00 kWh");
        powerLabel.setText("功率: 0.0 kW");
        timeLabel.setText("时长: 0 min");
        qrCodeLabel.setIcon(null);
        qrCodeLabel.setText("（等待充电启动后生成）");

        chargerCombo.setEnabled(true);
        refreshChargersBtn.setEnabled(true);
        plugButton.setEnabled(true);
        unplugButton.setEnabled(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(false);

        setStatusText("就绪 - 请选择充电桩并插枪", COLOR_IDLE);
    }

    /**
     * Periodically called by the UI timer to refresh energy display.
     */
    public void refreshFromSimulator(ChargeSimulator sim) {
        if (sim == null || !sim.isCharging()) {
            return;
        }
        double energy = sim.getCurrentEnergy();
        long seconds = sim.getElapsedSeconds();
        double power = 0.0;
        if (seconds > 0) {
            power = (energy / seconds) * 3600.0 / 1000.0; // kW
        }

        int progress = (int) ((energy / 50.0) * 100);
        progressBar.setValue(Math.min(progress, 100));
        progressBar.setString(String.format("%.1f / 50.0 kWh", energy));

        energyLabel.setText(String.format("电量: %.2f kWh", energy));
        powerLabel.setText(String.format("功率: %.1f kW", power));
        timeLabel.setText(String.format("时长: %d min %d sec", seconds / 60, seconds % 60));
    }

    // ===== Internal =====

    private void setPluggedIn(boolean plugged) {
        this.pluggedIn = plugged;
        plugButton.setEnabled(!plugged);
        unplugButton.setEnabled(plugged);
        startButton.setEnabled(plugged && !charging && currentChargerId != null);
        stopButton.setEnabled(charging);
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
        } else {
            currentChargerId = null;
            plugButton.setEnabled(false);
        }
    }

    /**
     * Generate a QR code containing charger identity for Flutter to scan.
     * Flutter scans this QR and calls the backend start-charge API directly.
     */
    public void generateQrForCharger(String chargerId) {
        String qrData = String.format(
                "{\"chargerId\":\"%s\",\"stationName\":\"%s\"}",
                chargerId,
                chargerCombo.getSelectedItem() != null
                        ? chargerCombo.getSelectedItem().toString()
                        : "unknown"
        );

        BufferedImage qrImage = QrCodeGenerator.generateQR(qrData, 200, 200);
        if (qrImage != null) {
            qrCodeLabel.setIcon(new ImageIcon(qrImage));
            qrCodeLabel.setText("");
        } else {
            qrCodeLabel.setText("QR生成失败");
        }
    }

    private void generateQrForRecord(ChargeRecord record) {
        String qrData = String.format(
                "{\"chargerId\":\"%s\",\"recordId\":\"%s\",\"sessionToken\":\"mock_%s\"}",
                record.getChargerId(),
                record.getId(),
                abbreviateId(record.getId())
        );

        BufferedImage qrImage = QrCodeGenerator.generateQR(qrData, 200, 200);
        if (qrImage != null) {
            qrCodeLabel.setIcon(new ImageIcon(qrImage));
            qrCodeLabel.setText("");
        } else {
            qrCodeLabel.setText("QR生成失败");
        }
    }

    private static String abbreviateId(String uuid) {
        if (uuid == null || uuid.length() < 8) return uuid;
        return uuid.substring(0, 8);
    }

    // ===== Accessors =====

    public boolean isCharging() {
        return charging;
    }

    public String getCurrentRecordId() {
        return currentRecordId;
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

    // ===== Callback interface =====

    /**
     * Callbacks that the panel invokes when buttons are pressed.
     * Implemented by {@link MockChargerClient}.
     */
    public interface ChargerUICallbacks {
        void onRefreshChargers();
        boolean onPlugIn(String chargerId);
        boolean onUnplug();
        void onStartCharge(String chargerId);
        void onStopCharge();
    }
}