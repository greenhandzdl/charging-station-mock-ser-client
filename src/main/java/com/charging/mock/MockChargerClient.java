package com.charging.mock;

import com.charging.mock.config.AppConfig;
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
 * MockChargerClient is the main entry point for the Swing desktop application
 * that simulates a physical charging station interaction.
 *
 * <p>This client:
 * <ul>
 *   <li>Authenticates with the backend using mock credentials</li>
 *   <li>Fetches available chargers and displays them in a combo box</li>
 *   <li>Simulates plug/unplug and start/stop charge workflows</li>
 *   <li>Generates QR codes from session data (scan-able by the Flutter client)</li>
 *   <li>Uses a timer to update the UI from the {@link ChargeSimulator}</li>
 * </ul>
 *
 * <p>Security: The JWT obtained during login has scope {@code mock_charger_only}.
 * The backend Nginx and Spring Security enforce that this scope can only access
 * {@code /api/v1/charges/*} endpoints — management, user, and analytics APIs
 * are blocked. The client uses an isolated test user that does not affect real data.
 *
 * @see ChargerUIPanel
 * @see ChargeSimulator
 * @see ApiClient
 */
public class MockChargerClient extends JFrame implements ChargerUIPanel.ChargerUICallbacks {

    private final ChargerUIPanel chargerPanel;
    private final ChargeSimulator chargeSimulator;
    private final ApiClient apiClient;

    private Timer uiTimer;
    private boolean authenticated;
    private String testUserId;

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
        setMinimumSize(new Dimension(520, 680));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(this::performStartup);
            }

            private void performStartup() {
                boolean ok = doLogin();
                if (ok) {
                    loadChargers();
                }
            }
        });
    }

    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File menu
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

        // Simulation menu
        JMenu simMenu = new JMenu("Simulation");
        JMenuItem startSimItem = new JMenuItem("Start Simulation");
        startSimItem.addActionListener(e -> startChargingFlow());
        simMenu.add(startSimItem);

        JMenuItem stopSimItem = new JMenuItem("Stop Simulation");
        stopSimItem.addActionListener(e -> stopChargingFlow());
        simMenu.add(stopSimItem);

        simMenu.addSeparator();
        JMenuItem refreshItem = new JMenuItem("Refresh Chargers");
        refreshItem.addActionListener(e -> loadChargers());
        simMenu.add(refreshItem);

        menuBar.add(simMenu);

        setJMenuBar(menuBar);
    }

    private void initUiTimer() {
        uiTimer = new Timer(1000, this::onUiTick);
    }

    // ===== Startup =====

    private boolean doLogin() {
        try {
            String token = apiClient.login(AppConfig.MOCK_USER_USERNAME, AppConfig.MOCK_USER_PASSWORD);
            this.authenticated = true;
            // The backend returns the userId inside the JWT; we extract it from the token or
            // a dedicated login response field. For the mock client we parse the token's "sub" claim.
            // Simplified: we just store that we're authenticated.
            System.out.println("Login successful. Token: " + token.substring(0, Math.min(20, token.length())) + "...");
            setTitle("Mock Charger Client - 已认证 [" + AppConfig.MOCK_USER_USERNAME + "]");
            return true;
        } catch (ApiException e) {
            JOptionPane.showMessageDialog(this,
                    "登录失败: " + e.getMessage(),
                    "认证错误", JOptionPane.ERROR_MESSAGE);
            this.authenticated = false;
            return false;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "无法连接后端: " + e.getMessage(),
                    "连接错误", JOptionPane.ERROR_MESSAGE);
            this.authenticated = false;
            return false;
        }
    }

    private void loadChargers() {
        if (!authenticated) {
            chargerPanel.showError("未认证", "请先登录后端服务");
            return;
        }
        try {
            List<Map<String, Object>> chargers = apiClient.getChargers();
            chargerPanel.setChargerList(chargers);
            System.out.println("Loaded " + chargers.size() + " chargers");
        } catch (ApiException e) {
            chargerPanel.showError("获取充电桩失败", e.getMessage());
        } catch (Exception e) {
            chargerPanel.showError("连接错误", e.getMessage());
        }
    }

    // ===== UI Timer tick =====

    private void onUiTick(ActionEvent e) {
        if (chargeSimulator.isCharging()) {
            chargeSimulator.tick();
            chargerPanel.refreshFromSimulator(chargeSimulator);
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
        return true;
    }

    @Override
    public boolean onUnplug() {
        return true;
    }

    @Override
    public void onStartCharge(String chargerId) {
        // This is triggered when the user clicks "Start Charge" after plugging in
        startChargingFlow();
    }

    @Override
    public void onStopCharge() {
        stopChargingFlow();
    }

    // ===== Core charge flow =====

    private void startChargingFlow() {
        if (!authenticated) {
            chargerPanel.showError("未认证", "请重新登录");
            return;
        }
        if (chargeSimulator.isCharging()) {
            return;
        }

        String chargerId = getSelectedChargerId();
        if (chargerId == null) {
            JOptionPane.showMessageDialog(chargerPanel, "请先选择充电桩",
                    "操作提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Call backend API
        try {
            ChargeRecord record = apiClient.startCharge(chargerId);
            System.out.println("Charge started: " + record.getId());

            // Start the simulator
            chargeSimulator.startSimulation();
            chargerPanel.onChargeStarted(record, chargeSimulator);

            // Start UI timer
            uiTimer.start();

        } catch (ApiException e) {
            // Handle specific HTTP codes from the UML sequence diagram
            String userMsg;
            if (e.getStatusCode() == 402) {
                userMsg = "余额不足，需至少 10 元才能启动充电";
            } else if (e.getStatusCode() == 403) {
                userMsg = "账户已冻结，无法启动充电。请充值解冻。";
            } else if (e.getStatusCode() == 409) {
                userMsg = "充电桩已被占用，请选择其他充电桩";
            } else {
                userMsg = "启动充电失败: " + e.getMessage();
            }
            chargerPanel.showError("启动充电失败", userMsg);
        } catch (Exception e) {
            chargerPanel.showError("网络错误", "无法连接到后端服务: " + e.getMessage());
        }
    }

    private void stopChargingFlow() {
        if (!authenticated) {
            chargerPanel.showError("未认证", "请重新登录");
            return;
        }
        if (!chargeSimulator.isCharging()) {
            return;
        }

        String recordId = chargerPanel.getCurrentRecordId();
        if (recordId == null) {
            chargerPanel.showError("错误", "没有活跃的充电记录");
            return;
        }

        // Stop the simulator first to get final values
        ChargeSimulator.SimulationResult result = chargeSimulator.stopSimulation();
        uiTimer.stop();

        // Call backend API
        try {
            ChargeRecord record = apiClient.stopCharge(recordId);
            System.out.println("Charge stopped: fee=" + record.getFee()
                    + ", energy=" + record.getEnergyKwh()
                    + ", deduction=" + record.getDeductionStatus());

            chargerPanel.onChargeStopped(record, result);

        } catch (ApiException e) {
            // Even if the API fails, we still show simulated results
            String msg = String.format(
                    "后端通知失败 (HTTP %d)，但本地模拟已完成:\n电量: %.2f kWh\n费用: %.2f 元\n\n后端错误: %s",
                    e.getStatusCode(), result.getEnergyKwh(), result.getFee(), e.getMessage());
            JOptionPane.showMessageDialog(chargerPanel, msg, "停止充电警告",
                    JOptionPane.WARNING_MESSAGE);
            chargerPanel.setStatusText("停止充电 (API异常)", Color.ORANGE);
        } catch (Exception e) {
            chargerPanel.showError("网络错误", "停止充电请求失败: " + e.getMessage());
        }
    }

    private String getSelectedChargerId() {
        ChargerUIPanel.ChargerItem item =
                (ChargerUIPanel.ChargerItem) chargerPanel.chargerCombo.getSelectedItem();
        return item != null ? item.id : null;
    }

    // ===== Main entry point =====

    /**
     * Launch the Mock Charger Client application.
     *
     * @param args optional arguments (not used)
     */
    public static void main(String[] args) {
        // Set system look and feel for a more native appearance
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fall back to cross-platform if system L&F fails
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {
                // Default look and feel
            }
        }

        SwingUtilities.invokeLater(() -> {
            MockChargerClient client = new MockChargerClient();
            client.setVisible(true);
        });
    }
}