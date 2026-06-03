package com.charging.mock.config;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NetworkSimulator manages a simulated network state for the Mock Charger Client.
 *
 * <p>When {@code isOffline} is {@code true}, all API calls should fail immediately
 * with an {@link java.io.IOException} instead of making actual HTTP requests.
 * This allows testers to simulate network outages, server restarts, etc.
 *
 * <p>The heartbeat timer and test scenario actions interact with this class
 * to toggle connectivity and observe the effects on the UI.
 */
public final class NetworkSimulator {

    private static final AtomicBoolean offline = new AtomicBoolean(false);

    private NetworkSimulator() {
    }

    /**
     * @return {@code true} if the network is currently simulated as offline
     */
    public static boolean isOffline() {
        return offline.get();
    }

    /**
     * Set the simulated network state.
     *
     * @param offlineMode {@code true} to simulate network disconnection
     */
    public static void setOffline(boolean offlineMode) {
        offline.set(offlineMode);
        System.out.println("[NetworkSimulator] Network " + (offlineMode ? "OFFLINE" : "ONLINE"));
    }

    /**
     * Toggle the current network state.
     *
     * @return the new state ({@code true} = offline)
     */
    public static boolean toggle() {
        boolean newState = !offline.get();
        setOffline(newState);
        return newState;
    }
}