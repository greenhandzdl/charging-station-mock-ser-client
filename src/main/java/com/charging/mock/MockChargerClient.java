package com.charging.mock;

import com.charging.mock.config.AppConfig;
import com.charging.mock.config.NetworkSimulator;
import com.charging.mock.config.TestDataProvider;
import com.charging.mock.model.ChargeRecord;
import com.charging.mock.service.ApiClient;
import com.charging.mock.service.ChargeSimulator;
import com.charging.mock.service.ChargerHttpServer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *   <li>Send periodic heartbeat to monitor backend connectivity</li>
 *   <li>Receive push notifications from the backend via {@link ChargerHttpServer}</li>
 *   <li>Simulate energy accumulation during charging via {@link ChargeSimulator}</li>
 * </ul>
 *
 * <p>Important: This client does NOT call start/stop charge APIs directly for user
 * interactions — those are handled by the Flutter client after scanning the QR code,
 * just like a real charging station where the phone app controls activation and payment.
 * However, when a start/stop notification arrives via ChargerHttpServer, this client
 * will simulate the local energy display. On unplug while charging is active, it will
 * call the stop-charge API to notify the backend.
 *
 * <p>Two permission modes are supported:
 * <ul>
 *   <li><b>Normal</b> — standard mock_user/mock123 JWT login</li>
 *   <li><b>Advanced</b> — API Key authentication with elevated privileges
 *       (enabled by setting the {@code ADVANCED_API_KEY} environment variable)</li>
 * </ul>
 */
public class MockChargerClient extends JFrame
        implements ChargerUIPanel.ChargerUICallbacks, ChargerUIPanel.TestScenarioActions {

    private static final int HEARTBEAT_INTERVAL_MS = 30_000; // 30 seconds

    // Advanced mode UI colors
    private static final Color COLOR_ADVANCED_BG = new Color(0xE8, 0xEE, 0xFF); // light blue background

    private final ChargerUIPanel chargerPanel;
    private final ApiClient apiClient;
    private final ChargeSimulator chargeSimulator;
    private ChargerHttpServer httpServer;

    private Timer heartbeatTimer;
    private Timer uiUpdateTimer;
    private boolean authenticated;
    private boolean heartbeatAlive;
    private String selectedChargerId;
    private String currentChargeRecordId;
    private String currentSessionId;
    /** 从后端 API 获取的充电桩列表缓存 */
    private List<Map<String, Object>> fetchedChargers;

    public MockChargerClient() {
        super(buildTitle());

        this.apiClient = new ApiClient(AppConfig.BACKEND_URL);
        this.chargeSimulator = new ChargeSimulator();
        this.chargerPanel = new ChargerUIPanel(this, this);

        initFrame();
        initHeartbeatTimer();
    }

    /**
     * Build the initial window title based on permission mode.
     */
    private static String buildTitle() {
        if (AppConfig.USE_CHARGER_AUTH) {
            return "模拟充电站 [STATION_GLOBAL] - 充电站管理系统";
        }
        if (AppConfig.IS_ADVANCED_MODE) {
            return "Mock充电机 [高级模式] - 充电站管理系统";
        }
        return "Mock充电机 [普通模式] - 充电站管理系统";
    }

    private void initFrame() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(chargerPanel, BorderLayout.CENTER);

        // Advanced mode: add a subtle blue header bar
        if (AppConfig.IS_ADVANCED_MODE) {
            JPanel headerBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
            headerBar.setBackground(COLOR_ADVANCED_BG);
            headerBar.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            JLabel advancedLabel = new JLabel("高级权限模式 — 可见所有充电桩");
            advancedLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
            advancedLabel.setForeground(new Color(0x00, 0x44, 0xAA));
            headerBar.add(advancedLabel);
            add(headerBar, BorderLayout.NORTH);
        }

        pack();
        setMinimumSize(new Dimension(520, 740));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(this::performStartup);
            }

            private void performStartup() {
                doLogin();
                loadChargers();
                startHeartbeat();
                startHttpServer();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                stopHttpServer();
                stopTimers();
            }
        });
    }

    private void initHeartbeatTimer() {
        heartbeatTimer = new Timer(HEARTBEAT_INTERVAL_MS, e -> onHeartbeatTick());
        heartbeatTimer.setInitialDelay(1_000); // first heartbeat after 1s
    }

    // ===== HTTP Server (push notifications) =====

    /**
     * Start the ChargerHttpServer to receive push notifications from the backend.
     * Sets up callbacks to start/stop the local charge simulation.
     */
    private void startHttpServer() {
        httpServer = new ChargerHttpServer();
        httpServer.setOnStartCallback(notification -> {
            SwingUtilities.invokeLater(() -> {
                // Store the record ID and charger type; the actual simulation
                // will start when the user presses the plug button (onPlugIn).
                currentChargeRecordId = notification.getRecordId();
                chargeSimulator.setChargerType(
                        notification.getChargerType() != null ? notification.getChargerType() : "FAST");
                chargeSimulator.setCurrentSimulationId(notification.getRecordId());

                // Update UI to prompt user to plug in the cable
                chargerPanel.setStatusText("请插枪 — 桩: " + notification.getChargerCode(),
                        new Color(0xFF, 0xE4, 0xB5));
                chargerPanel.setPluggedIn(false);

                System.out.println("[HttpServer] Start notification received, waiting for plug-in: " + notification);
            });
        });
        httpServer.setOnStopCallback(notification -> {
            SwingUtilities.invokeLater(() -> {
                // Stop simulation
                ChargeSimulator.SimulationResult result = chargeSimulator.stopSimulation();
                currentChargeRecordId = null;

                // Stop UI timer
                if (uiUpdateTimer != null) uiUpdateTimer.stop();

                // Update UI
                chargerPanel.setStatusText("充电结束 - 电量: " + result.getEnergyKwh()
                        + " kWh, 费用: " + result.getFee() + " 元", new Color(0xFF, 0xE4, 0xB5));
                chargerPanel.showChargeResult(result.getEnergyKwh(), result.getFee());
                chargerPanel.setPluggedIn(false);

                // Reset simulator
                chargeSimulator.reset();

                System.out.println("[HttpServer] Charge stopped: " + notification + " result=" + result);
            });
        });
        httpServer.start();
        updateTitleBar();
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    private void stopTimers() {
        if (uiUpdateTimer != null) {
            uiUpdateTimer.stop();
            uiUpdateTimer = null;
        }
        if (heartbeatTimer != null) {
            heartbeatTimer.stop();
        }
    }

    /**
     * Update the charging info display on the UI panel.
     * Called every second by the UI update timer.
     */
    private void updateChargingUI() {
        if (!chargeSimulator.isCharging()) {
            return;
        }
        chargeSimulator.tick();
        double energy = chargeSimulator.getCurrentEnergy();
        long elapsedSeconds = chargeSimulator.getElapsedSeconds();

        // Calculate fee roughly to display (mirroring ChargeSimulator's internal logic)
        BigDecimal fee = BigDecimal.valueOf(energy)
                .multiply(new BigDecimal("1.5"))
                .setScale(2, RoundingMode.HALF_UP);

        chargerPanel.updateChargingUI(energy, fee, elapsedSeconds);
    }

    // ===== Heartbeat (simplified: just check connectivity) =====

    private void startHeartbeat() {
        if (!heartbeatTimer.isRunning()) {
            heartbeatTimer.start();
            System.out.println("[Heartbeat] Started (interval: " + HEARTBEAT_INTERVAL_MS + "ms)");
        }
    }

    private void stopHeartbeat() {
        if (heartbeatTimer.isRunning()) {
            heartbeatTimer.stop();
            System.out.println("[Heartbeat] Stopped");
        }
    }

    private void onHeartbeatTick() {
        // If offline mode is active, immediately mark heartbeat as failed
        if (NetworkSimulator.isOffline()) {
            setHeartbeatAlive(false);
            if (chargeSimulator.isCharging()) {
                ChargeSimulator.SimulationResult result = chargeSimulator.stopSimulation();
                chargerPanel.setPluggedIn(false);
                selectedChargerId = null;
                chargerPanel.setStatusText("离线中 — 充电已停止: " + result, new java.awt.Color(0xFF, 0xCC, 0xCC));
                System.out.println("[离线检测] 充电已停止: " + result);
            } else {
                chargerPanel.setStatusText("离线中", new java.awt.Color(0xFF, 0xCC, 0xCC));
            }
            return;
        }

        try {
            // Send heartbeat for ALL fetched chargers so all are marked ONLINE
            if (fetchedChargers == null || fetchedChargers.isEmpty()) {
                setHeartbeatAlive(true);
                return;
            }
            boolean allOk = true;
            for (Map<String, Object> c : fetchedChargers) {
                Object code = c.get("chargerCode");
                if (code == null) continue;
                try {
                    apiClient.sendHeartbeat(code.toString());
                } catch (Exception e) {
                    allOk = false;
                    System.out.println("[Heartbeat] FAILED for " + code + ": " + e.getMessage());
                }
            }
            setHeartbeatAlive(allOk);
        } catch (Exception ex) {
            setHeartbeatAlive(false);
            System.out.println("[Heartbeat] FAILED: " + ex.getMessage());
        }
    }

    /**
     * Get the charger code from the currently SELECTED combo box item.
     * Returns null if no valid charger is selected (placeholder item).
     */
    private String getSelectedChargerCode() {
        ChargerUIPanel.ChargerItem item = (ChargerUIPanel.ChargerItem) chargerPanel.chargerCombo.getSelectedItem();
        if (item == null || item.id == null) {
            return null;
        }
        // The ChargerItem.code field holds the charger code (e.g. "CY-A01")
        return item.code;
    }

    /**
     * 从充电桩列表缓存中按 id 查找充电桩。
     */
    private Map<String, Object> findChargerById(String chargerId) {
        if (fetchedChargers == null || chargerId == null) return null;
        return fetchedChargers.stream()
                .filter(c -> chargerId.equals(c.get("id")) || chargerId.equals(c.get("chargerId")))
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolve the charger code from the currently selected/plugged charger.
     * Falls back to the first available charger if none is selected.
     */
    private String getCurrentChargerCode() {
        // If a charger is plugged in, use its code
        if (selectedChargerId != null) {
            Map<String, Object> charger = findChargerById(selectedChargerId);
            if (charger != null) {
                Object code = charger.get("chargerCode");
                if (code != null) return code.toString();
                // Also try snake_case for API responses
                code = charger.get("charger_code");
                if (code != null) return code.toString();
            }
        }
        // Fallback: use first charger from fetched list or test data
        if (fetchedChargers != null && !fetchedChargers.isEmpty()) {
            Map<String, Object> first = fetchedChargers.get(0);
            Object code = first.get("chargerCode");
            if (code != null) return code.toString();
            code = first.get("charger_code");
            if (code != null) return code.toString();
        }
        return null;
    }

    private void setHeartbeatAlive(boolean alive) {
        this.heartbeatAlive = alive;
        updateTitleBar();
    }

    private void updateTitleBar() {
        StringBuilder sb = new StringBuilder();
        if (AppConfig.USE_CHARGER_AUTH) {
            sb.append("模拟充电站 [STATION_GLOBAL]");
        } else if (AppConfig.IS_ADVANCED_MODE) {
            sb.append("Mock充电机 [高级模式]");
        } else {
            sb.append("Mock充电机 [普通模式]");
        }

        if (authenticated) {
            sb.append(" - 已连接");
        } else {
            sb.append(" - 未连接");
        }
        if (heartbeatAlive) {
            sb.append(" [心跳正常]");
        } else {
            sb.append(" [心跳断开]");
        }

        if (httpServer != null) {
            sb.append(" [HTTP:8081]");
        }

        // Use invokeLater to ensure EDT safety
        final String title = sb.toString();
        SwingUtilities.invokeLater(() -> setTitle(title));
    }

    // ===== Test Scenarios =====

    /**
     * 断网测试: Enable offline mode for 15 seconds, then auto-reconnect.
     */
    private void runIntermittentNetworkTest() {
        SwingUtilities.invokeLater(() -> {
            chargerPanel.setStatusText("【测试】断网测试开始 — 模拟断网15秒",
                    Color.ORANGE);
        });

        NetworkSimulator.setOffline(true);
        updateTitleBar();

        Timer recoveryTimer = new Timer(15_000, evt -> {
            NetworkSimulator.setOffline(false);
            SwingUtilities.invokeLater(() -> {
                chargerPanel.setStatusText("【测试】断网测试结束 — 网络已恢复",
                        new Color(0x90, 0xEE, 0x90));
            });
            updateTitleBar();
        });
        recoveryTimer.setRepeats(false);
        recoveryTimer.start();
    }

    /**
     * 服务器重启测试: Simulate backend being down — heartbeat fails, then recovers after 20s.
     */
    private void runServerRestartTest() {
        SwingUtilities.invokeLater(() -> {
            chargerPanel.setStatusText("【测试】服务器重启测试 — 模拟服务器停机20秒",
                    Color.ORANGE);
        });

        // Save current network state to restore later
        boolean wasOffline = NetworkSimulator.isOffline();

        // Force offline to fail heartbeats
        NetworkSimulator.setOffline(true);
        updateTitleBar();

        Timer recoveryTimer = new Timer(20_000, evt -> {
            // Restore original network state
            NetworkSimulator.setOffline(wasOffline);
            SwingUtilities.invokeLater(() -> {
                chargerPanel.setStatusText("【测试】服务器重启测试结束 — 服务已恢复",
                        new Color(0x90, 0xEE, 0x90));
            });
            updateTitleBar();
        });
        recoveryTimer.setRepeats(false);
        recoveryTimer.start();
    }

    /**
     * 充电桩离线测试: Stop heartbeat and show charger as offline.
     */
    private void runChargerOfflineTest() {
        stopHeartbeat();
        setHeartbeatAlive(false);

        SwingUtilities.invokeLater(() -> {
            chargerPanel.setStatusText("【测试】充电桩已离线 — 心跳已停止",
                    Color.RED);
        });

        // After 20 seconds, ask if user wants to reconnect
        Timer reconnectPrompt = new Timer(20_000, evt -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "充电桩离线测试已过去20秒。是否重新连接？",
                    "充电桩离线测试", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                startHeartbeat();
                setHeartbeatAlive(true);
                SwingUtilities.invokeLater(() -> {
                    chargerPanel.setStatusText("【测试】充电桩已重新上线",
                            new Color(0x90, 0xEE, 0x90));
                });
            } else {
                SwingUtilities.invokeLater(() -> {
                    chargerPanel.setStatusText("【测试】充电桩保持离线状态",
                            Color.RED);
                });
            }
        });
        reconnectPrompt.setRepeats(false);
        reconnectPrompt.start();
    }

    // ===== Startup =====

    private void doLogin() {
        try {
            if (AppConfig.USE_CHARGER_AUTH) {
                // Use charger identity login (charger_users table, STATION_GLOBAL permission)
                String token = apiClient.chargerLogin(AppConfig.CHARGER_LOGIN_ID, AppConfig.CHARGER_PASSWORD);
                this.authenticated = true;
                System.out.println("[充电桩模式] Login successful. Identity: " + AppConfig.CHARGER_LOGIN_ID
                        + " Token: " + token.substring(0, Math.min(20, token.length())) + "...");
            } else {
                // Legacy user login (users table)
                String token = apiClient.login(AppConfig.MOCK_USER_USERNAME, AppConfig.MOCK_USER_PASSWORD);
                this.authenticated = true;
                String mode = AppConfig.IS_ADVANCED_MODE ? "高级" : "普通";
                System.out.println("[" + mode + "模式] Login successful. Token: "
                        + token.substring(0, Math.min(20, token.length())) + "...");
            }
            updateTitleBar();
        } catch (Exception e) {
            System.out.println("Backend login failed (non-fatal): " + e.getMessage());
            System.out.println("Mock will run with local test data only; polling sync disabled.");
            this.authenticated = false;
            updateTitleBar();
        }
    }

    /**
     * 加载充电桩列表。
     * <p>
     * 普通模式：仅使用本地测试数据（模拟真实充电桩只有自身信息）。
     * 高级模式：从后端 API 获取全量充电桩列表（测试环境允许的权限）。
     */
    private void loadChargers() {
        List<Map<String, Object>> chargers;
        String mode = AppConfig.IS_ADVANCED_MODE ? "高级" : "普通";

        if (AppConfig.IS_ADVANCED_MODE && authenticated) {
            // 高级模式：测试环境，从后端获取所有充电桩信息
            try {
                chargers = apiClient.getChargers();
                this.fetchedChargers = chargers;
                System.out.println("[" + mode + "模式] Loaded " + chargers.size()
                        + " chargers from backend API (full access)");
                chargerPanel.setChargerList(chargers);
                return;
            } catch (Exception e) {
                System.out.println("[" + mode + "模式] Backend API unavailable: " + e.getMessage());
                // fall through to local data
            }
        }

        // 普通模式或 API 不可用时：使用本地测试数据
        chargers = TestDataProvider.getChargers();
        this.fetchedChargers = chargers;
        System.out.println("[" + mode + "模式] Loaded " + chargers.size()
                + " chargers from local test data");
        chargerPanel.setChargerList(chargers);
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

        try {
            // Call backend plug-in API with charger JWT (X-Charger-Token)
            Map<String, Object> result = apiClient.plugInCharger(chargerId);
            currentSessionId = (String) result.get("sessionId");

            // Regenerate QR with sessionId
            chargerPanel.generateQrForCharger(chargerId, currentSessionId);

            chargerPanel.setPluggedIn(true);
            chargerPanel.setStatusText("已插枪 — 请使用Flutter App扫描二维码启动充电",
                    new Color(0xFF, 0xE4, 0xB5));
            System.out.println("[PlugIn] Charger " + chargerId + " plugged in, sessionId=" + currentSessionId);
            return true;
        } catch (Exception e) {
            chargerPanel.setStatusText("插枪失败: " + e.getMessage(), Color.PINK);
            System.out.println("[PlugIn] Failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean onUnplug() {
        String chargerIdToUnplug = selectedChargerId;
        if (chargerIdToUnplug == null) {
            // Requirement: unplug should ALWAYS be available.
            // If no charger was "locally" plugged in, try to release the currently selected one in the UI.
            ChargerUIPanel.ChargerItem item = (ChargerUIPanel.ChargerItem) chargerPanel.chargerCombo.getSelectedItem();
            if (item != null && item.id != null) {
                chargerIdToUnplug = item.id;
            }
        }

        if (chargerIdToUnplug != null) {
            try {
                // Call backend unplug API with charger JWT
                Map<String, Object> result = apiClient.unplugCharger(chargerIdToUnplug);
                System.out.println("[Unplug] Charger released via API: " + result);
            } catch (Exception e) {
                System.err.println("[Unplug] Failed to call unplug API (ignoring to allow local reset): " + e.getMessage());
            }

            // Stop any active simulation
            if (currentChargeRecordId != null) {
                chargeSimulator.stopSimulation();
                currentChargeRecordId = null;
            }
            if (uiUpdateTimer != null) {
                uiUpdateTimer.stop();
            }
            chargeSimulator.reset();
        }

        selectedChargerId = null;
        currentSessionId = null;
        chargerPanel.resetToIdle();
        return true;
    }

    // ===== ChargerUICallbacks: onApplySelected =====

    @Override
    public void onApplySelected(List<String> selectedChargerIds) {
        if (selectedChargerIds.isEmpty()) {
            JOptionPane.showMessageDialog(chargerPanel, "请在列表中勾选要模拟的充电桩",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        System.out.println("[应用] 已选择 " + selectedChargerIds.size() + " 个充电桩用于模拟: " + selectedChargerIds);
        chargerPanel.setStatusText("已应用 " + selectedChargerIds.size() + " 个充电桩模拟", new Color(0xCC, 0xFF, 0xCC));
    }

    // ===== TestScenarioActions implementation =====

    @Override
    public void onIntermittentNetwork() {
        runIntermittentNetworkTest();
    }

    @Override
    public void onServerRestart() {
        runServerRestartTest();
    }

    @Override
    public void onChargerOffline() {
        runChargerOfflineTest();
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
