package com.charging.mock;

import com.charging.mock.config.AppConfig;
import com.charging.mock.config.TestDataProvider;
import com.charging.mock.model.ChargeRecord;
import com.charging.mock.service.ApiClient;
import com.charging.mock.service.ApiClient.ApiException;
import com.charging.mock.service.ChargeSimulator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;

/**
 * MockChargerClient is a Swing desktop application that simulates the display panel
 * of a physical charging station.
 *
 * <p>This simulates a real charging station screen. Its responsibilities:
 * <ul>
 *   <li>Display available chargers (simulating the charger's built-in screen)</li>
 *   <li>Simulate plug/unplug cable interaction</li>
 *   <li>Generate a QR code containing charger info for the Flutter app to scan</li>
 *   <li>Display real-time charging progress (energy, time, power) once charge is active</li>
 *   <li>Poll backend for charge status to stay in sync with Flutter-started sessions</li>
 * </ul>
 *
 * <p>Important: This client does NOT call start/stop charge APIs — those are
 * handled by the Flutter client after scanning the QR code, just like a real
 * charging station where the phone app controls activation and payment.
 */
public class MockChargerClient extends JFrame implements ChargerUIPanel.ChargerUICallbacks {

    private final ChargerUIPanel chargerPanel;
    private final ChargeSimulator chargeSimulator;
    private final ApiClient apiClient;

    private Timer uiTimer;
    private boolean authenticated;
    private String selectedChargerId;

    public MockChargerClient() {
        super("Mock Charger Client - 充电站管理系统");

        this.chargeSimulator = new ChargeSimulator();
        this.apiClient = new ApiClient(AppConfig.BACKEND_URL);
        this.chargerPanel = new ChargerUIPanel(this);

        initFrame();
        initMenuBar();
        initUiTimer();
    }

    private void initFrame() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(chargerPanel, BorderLayout.CENTER);
        pack();
        setMinimumSize(new Dimension(520, 720));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(this::performStartup);
            }

            private void performStartup() {
                // Attempt optional login for polling sync (non-fatal if backend is down).
                // Charger data is always loaded from local test data.
                doLogin();
                loadChargers();
            }
        });
    }

    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setAccelerator(KeyStroke.getKeyStroke("ctrl Q"));
        exitItem.addActionListener(e -> {
            if (chargeSimulator.isCharging()) {
                int r = JOptionPane.showConfirmDialog(this,
                        "充电进行中，确定退出？", "确认退出",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.YES_OPTION) return;
            }
            System.exit(0);
        });
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);

        JMenu simMenu = new JMenu("操作");
        JMenuItem resetItem = new JMenuItem("重置充电桩状态");
        resetItem.addActionListener(e -> {
            loadChargers();
            chargerPanel.setStatusText("充电桩状态已重置", new Color(0x90, 0xEE, 0x90));
        });
        simMenu.add(resetItem);

        JMenuItem pollItem = new JMenuItem("手动轮询充电状态");
        pollItem.setEnabled(false);
        pollItem.addActionListener(e -> pollAndSync());
        simMenu.add(pollItem);

        menuBar.add(simMenu);

        setJMenuBar(menuBar);
    }

    private void initUiTimer() {
        uiTimer = new Timer(1000, this::onUiTick);
    }

    // ===== Startup =====

    private void doLogin() {
        try {
            String token = apiClient.login(AppConfig.MOCK_USER_USERNAME, AppConfig.MOCK_USER_PASSWORD);
            this.authenticated = true;
            System.out.println("Login successful. Token: " + token.substring(0, Math.min(20, token.length())) + "...");
            setTitle("Mock Charger Client - 已认证 [" + AppConfig.MOCK_USER_USERNAME + "]");
        } catch (Exception e) {
            System.out.println("Backend login failed (non-fatal): " + e.getMessage());
            System.out.println("Mock will run with local test data only; polling sync disabled.");
            this.authenticated = false;
        }
    }

    /**
     * Load charger data from local test data provider.
     * No backend API call is needed — this simulates the charger's built-in
     * hardware configuration (station name, charger code, type, etc.).
     */
    private void loadChargers() {
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        chargerPanel.setChargerList(chargers);
        System.out.println("Loaded " + chargers.size() + " chargers from local test data");
    }

    // ===== UI Timer tick =====

    private void onUiTick(ActionEvent e) {
        if (chargeSimulator.isCharging()) {
            chargeSimulator.tick();
            chargerPanel.refreshFromSimulator(chargeSimulator);
            // Poll backend periodically to sync with Flutter's charge session
            pollAndSync();
        }
    }

    // ===== ChargerUICallbacks implementation =====

    @Override
    public void onRefreshChargers() {
        loadChargers();
    }

    @Override
    public boolean onPlugIn(String chargerId) {
        if (chargerId == null) {
            JOptionPane.showMessageDialog(chargerPanel, "请先选择充电桩",
                    "操作提示", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        selectedChargerId = chargerId;
        // Generate QR code for Flutter to scan — contains charger ID info
        chargerPanel.generateQrForCharger(chargerId);
        chargerPanel.setStatusText("已插枪 — 请使用Flutter App扫描二维码启动充电",
                new Color(0xFF, 0xE4, 0xB5));
        return true;
    }

    @Override
    public boolean onUnplug() {
        if (chargeSimulator.isCharging()) {
            JOptionPane.showMessageDialog(chargerPanel,
                    "充电进行中，请使用Flutter App停止充电后再拔枪",
                    "操作提示", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        selectedChargerId = null;
        chargeSimulator.reset();
        chargerPanel.resetToIdle();
        return true;
    }

    @Override
    public void onStartCharge(String chargerId) {
        // NOTE: start charge is initiated by Flutter scanning QR and calling the API.
        // This button is a fallback for testing without Flutter.
        // Instead of calling the API directly, show QR and tell user to scan.
        chargerPanel.generateQrForCharger(chargerId);
        chargerPanel.setStatusText(
                "请使用Flutter App扫描下方二维码启动充电",
                Color.ORANGE);
    }

    @Override
    public void onStopCharge() {
        // NOTE: stop charge is initiated by Flutter calling the API.
        // The Mock charger just displays the current state.
        // This button is a fallback: it will local-stop and tell user.
        if (!chargeSimulator.isCharging()) {
            chargerPanel.showError("未在充电", "当前无活跃充电会话");
            return;
        }
        chargerPanel.setStatusText("请使用Flutter App停止充电", Color.ORANGE);
    }

    // ===== Backend sync =====

    /**
     * Poll the backend for the current charge record status.
     * This keeps the Mock charger display in sync with the Flutter-started session.
     */
    private void pollAndSync() {
        if (!authenticated || chargeSimulator.getCurrentSimulationId() == null) return;

        try {
            ChargeRecord record = apiClient.getChargeStatus(chargeSimulator.getCurrentSimulationId());
            if ("COMPLETED".equals(record.getStatus())) {
                // Session ended by Flutter — stop local simulation
                ChargeSimulator.SimulationResult result = chargeSimulator.stopSimulation();
                uiTimer.stop();
                chargerPanel.onChargeStopped(record, result);
            }
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) {
                // Record not found — simulation was reset
                chargeSimulator.reset();
            }
            // Other errors: keep simulating silently
        } catch (Exception ignored) {
            // Network errors during polling are non-fatal
        }
    }

    // ===== Main entry point =====

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }

        SwingUtilities.invokeLater(() -> {
            MockChargerClient client = new MockChargerClient();
            client.setVisible(true);
        });
    }
}