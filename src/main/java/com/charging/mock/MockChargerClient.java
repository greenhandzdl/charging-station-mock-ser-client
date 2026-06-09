package com.charging.mock;

import com.charging.mock.config.AppConfig;
import com.charging.mock.config.NetworkSimulator;
import com.charging.mock.config.TestDataProvider;
import com.charging.mock.service.ApiClient;

import javax.swing.*;
import java.awt.*;
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
 *   <li>Send periodic heartbeat to monitor backend connectivity</li>
 * </ul>
 *
 * <p>Important: This client does NOT call start/stop charge APIs — those are
 * handled by the Flutter client after scanning the QR code, just like a real
 * charging station where the phone app controls activation and payment.
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

    private Timer heartbeatTimer;
    private boolean authenticated;
    private boolean heartbeatAlive;
    private String selectedChargerId;
    /** 从后端 API 获取的充电桩列表缓存 */
    private List<Map<String, Object>> fetchedChargers;

    public MockChargerClient() {
        super(buildTitle());

        this.apiClient = new ApiClient(AppConfig.BACKEND_URL);
        this.chargerPanel = new ChargerUIPanel(this, this);

        initFrame();
        initHeartbeatTimer();
    }

    /**
     * Build the initial window title based on permission mode.
     */
    private static String buildTitle() {
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
            }
        });
    }

    private void initHeartbeatTimer() {
        heartbeatTimer = new Timer(HEARTBEAT_INTERVAL_MS, e -> onHeartbeatTick());
        heartbeatTimer.setInitialDelay(5_000); // first heartbeat after 5s
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
            return;
        }

        try {
            String code = getCurrentChargerCode();
            if (code != null) {
                apiClient.sendHeartbeat(code);
            }
            setHeartbeatAlive(true);
        } catch (Exception ex) {
            setHeartbeatAlive(false);
            System.out.println("[Heartbeat] FAILED: " + ex.getMessage());
        }
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
        if (AppConfig.IS_ADVANCED_MODE) {
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
            String token = apiClient.login(AppConfig.MOCK_USER_USERNAME, AppConfig.MOCK_USER_PASSWORD);
            this.authenticated = true;

            String mode = AppConfig.IS_ADVANCED_MODE ? "高级" : "普通";
            System.out.println("[" + mode + "模式] Login successful. Token: "
                    + token.substring(0, Math.min(20, token.length())) + "...");

            // Update title with mode prefix
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
        // Regenerate QR for the plugged charger
        chargerPanel.generateQrForCharger(chargerId);
        chargerPanel.setStatusText("已插枪 — 请使用Flutter App扫描二维码启动充电",
                new Color(0xFF, 0xE4, 0xB5));
        return true;
    }

    @Override
    public boolean onUnplug() {
        selectedChargerId = null;
        chargerPanel.resetToIdle();
        return true;
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
