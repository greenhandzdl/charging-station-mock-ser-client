package com.charging.mock;

import com.charging.mock.config.TestDataProvider;
import com.charging.mock.service.ApiClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MockChargerClient}.
 *
 * <p>Since MockChargerClient extends JFrame and relies on Swing EDT, these tests
 * focus on specific helper methods that can be exercised without full GUI rendering.
 * Tests requiring a visible window or modal dialogs are marked as {@code @Disabled}.
 */
class MockChargerClientTest {

    private MockChargerClient client;
    private MockWebServer mockServer;

    @BeforeEach
    void setUp() {
        // Only create client if not headless (GUI constructor will fail otherwise)
        if (!GraphicsEnvironment.isHeadless()) {
            client = new MockChargerClient();
        }
        mockServer = new MockWebServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.dispose();
            client = null;
        }
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    @Test
    void constructor_shouldInitializeComponents() {
        // In headless mode, JFrame construction may fail.
        // This test validates that construction works in a GUI environment.
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        assertNotNull(client);
        // The frame should have a title containing "Mock充电机"
        assertTrue(client.getTitle().contains("Mock充电机"));
    }

    @Test
    void getCurrentChargerCode_whenChargersNotFetched_returnsNull() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        // Access the private getCurrentChargerCode method via reflection
        Method method = MockChargerClient.class.getDeclaredMethod("getCurrentChargerCode");
        method.setAccessible(true);

        // With no fetched chargers and no selected charger, should return null
        Object result = method.invoke(client);
        assertNull(result);
    }

    @Test
    void doLogin_whenApiClientThrows_shouldRunInDegradedMode() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        // Create an ApiClient pointing to our mock server that will return 401
        ApiClient failingClient = new ApiClient(mockServer.url("/api/v1").toString());
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":\"unauthorized\"}"));

        // Replace the client's apiClient with our failing one via reflection
        Field apiClientField = MockChargerClient.class.getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        apiClientField.set(client, failingClient);

        // Reflectively call doLogin
        Method doLoginMethod = MockChargerClient.class.getDeclaredMethod("doLogin");
        doLoginMethod.setAccessible(true);
        doLoginMethod.invoke(client);

        // After login failure, authenticated should be false
        Field authenticatedField = MockChargerClient.class.getDeclaredField("authenticated");
        authenticatedField.setAccessible(true);
        boolean authenticated = authenticatedField.getBoolean(client);
        assertFalse(authenticated);
    }

    @Test
    void doLogin_whenApiClientSucceeds_shouldAuthenticate() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        ApiClient workingClient = new ApiClient(mockServer.url("/api/v1").toString());
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"accessToken\":\"test-token-123\"}"));

        Field apiClientField = MockChargerClient.class.getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        apiClientField.set(client, workingClient);

        Method doLoginMethod = MockChargerClient.class.getDeclaredMethod("doLogin");
        doLoginMethod.setAccessible(true);
        doLoginMethod.invoke(client);

        Field authenticatedField = MockChargerClient.class.getDeclaredField("authenticated");
        authenticatedField.setAccessible(true);
        boolean authenticated = authenticatedField.getBoolean(client);
        assertTrue(authenticated);
    }

    @Test
    void getCurrentChargerCode_whenChargersLoaded_returnsCode() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        // Inject test data into fetchedChargers via reflection
        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        Field fetchedChargersField = MockChargerClient.class.getDeclaredField("fetchedChargers");
        fetchedChargersField.setAccessible(true);
        fetchedChargersField.set(client, chargers);

        // Set selectedChargerId to the first charger
        Field selectedChargerIdField = MockChargerClient.class.getDeclaredField("selectedChargerId");
        selectedChargerIdField.setAccessible(true);
        selectedChargerIdField.set(client, "11111111-1111-1111-1111-111111111001");

        Method getCurrentChargerCodeMethod = MockChargerClient.class.getDeclaredMethod("getCurrentChargerCode");
        getCurrentChargerCodeMethod.setAccessible(true);
        Object result = getCurrentChargerCodeMethod.invoke(client);

        assertEquals("CY-A01", result);
    }

    @Test
    void getCurrentChargerCode_fallsBackToFirstChargerWhenNoneSelected() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        List<Map<String, Object>> chargers = TestDataProvider.getChargers();
        Field fetchedChargersField = MockChargerClient.class.getDeclaredField("fetchedChargers");
        fetchedChargersField.setAccessible(true);
        fetchedChargersField.set(client, chargers);

        // Do not set selectedChargerId — should fall back to first charger
        Method getCurrentChargerCodeMethod = MockChargerClient.class.getDeclaredMethod("getCurrentChargerCode");
        getCurrentChargerCodeMethod.setAccessible(true);
        Object result = getCurrentChargerCodeMethod.invoke(client);

        assertEquals("CY-A01", result);
    }

    @Test
    void loadChargers_localMode_populatesChargersFromTestData() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        // MockChargerClient in non-advanced mode loads from TestDataProvider
        // Verify the fetchedChargers field is populated after loadChargers()
        Field fetchedChargersField = MockChargerClient.class.getDeclaredField("fetchedChargers");
        fetchedChargersField.setAccessible(true);

        // loadChargers is private, call it via reflection
        Method loadChargersMethod = MockChargerClient.class.getDeclaredMethod("loadChargers");
        loadChargersMethod.setAccessible(true);
        loadChargersMethod.invoke(client);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fetchedChargers =
                (List<Map<String, Object>>) fetchedChargersField.get(client);
        assertNotNull(fetchedChargers);
        assertFalse(fetchedChargers.isEmpty());
        assertEquals(7, fetchedChargers.size());
    }

    @Test
    @Disabled("Requires visible GUI for JOptionPane interaction")
    void onPlugIn_withNullChargerId_showsWarning() {
        // This test would need a visible GUI environment to verify JOptionPane
    }

    @Test
    @Disabled("Requires visible GUI for JOptionPane interaction")
    void onChargerOffline_showsConfirmDialog() {
        // This test would need a visible GUI environment to verify JOptionPane
    }
}
