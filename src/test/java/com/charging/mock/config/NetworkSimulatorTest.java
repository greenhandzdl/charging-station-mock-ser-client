package com.charging.mock.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NetworkSimulator}.
 *
 * <p>Validates thread-safe offline/online state management using AtomicBoolean.
 * Since the class uses static state, tests reset state in {@link #setUp()}
 * to ensure isolation.
 */
class NetworkSimulatorTest {

    @BeforeEach
    void setUp() {
        NetworkSimulator.setOffline(false);
    }

    @Test
    void isOffline_initialState_shouldBeFalse() {
        assertFalse(NetworkSimulator.isOffline());
    }

    @Test
    void setOffline_true_shouldMakeSimulatorOffline() {
        NetworkSimulator.setOffline(true);
        assertTrue(NetworkSimulator.isOffline());
    }

    @Test
    void setOffline_false_shouldMakeSimulatorOnlineAgain() {
        NetworkSimulator.setOffline(true);
        assertTrue(NetworkSimulator.isOffline());

        NetworkSimulator.setOffline(false);
        assertFalse(NetworkSimulator.isOffline());
    }

    @Test
    void toggle_shouldFlipStateFromOnlineToOffline() {
        assertFalse(NetworkSimulator.isOffline());

        boolean newState = NetworkSimulator.toggle();
        assertTrue(newState);
        assertTrue(NetworkSimulator.isOffline());
    }

    @Test
    void toggle_shouldFlipStateFromOfflineToOnline() {
        NetworkSimulator.setOffline(true);

        boolean newState = NetworkSimulator.toggle();
        assertFalse(newState);
        assertFalse(NetworkSimulator.isOffline());
    }

    @Test
    void toggle_multipleTimes_shouldAlternate() {
        boolean state1 = NetworkSimulator.toggle();
        assertTrue(state1);
        assertTrue(NetworkSimulator.isOffline());

        boolean state2 = NetworkSimulator.toggle();
        assertFalse(state2);
        assertFalse(NetworkSimulator.isOffline());

        boolean state3 = NetworkSimulator.toggle();
        assertTrue(state3);
        assertTrue(NetworkSimulator.isOffline());
    }

    @Test
    void concurrentAccess_shouldNotThrow() throws Exception {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicBoolean anyError = new AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 100; j++) {
                        NetworkSimulator.isOffline();
                        NetworkSimulator.toggle();
                        NetworkSimulator.isOffline();
                        NetworkSimulator.setOffline(threadId % 2 == 0);
                        NetworkSimulator.isOffline();
                    }
                } catch (Exception e) {
                    anyError.set(true);
                } finally {
                    doneLatch.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertFalse(anyError.get(), "No exception should occur during concurrent access");
    }
}
