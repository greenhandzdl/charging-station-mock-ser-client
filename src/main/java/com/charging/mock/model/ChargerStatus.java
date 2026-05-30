package com.charging.mock.model;

/**
 * Charger status enum matching the backend {@code ChargerStatus} definition.
 * <ul>
 *   <li>{@link #IDLE} — charger is available</li>
 *   <li>{@link #CHARGING} — charger is in use</li>
 *   <li>{@link #FAULT} — charger has a fault</li>
 * </ul>
 */
public enum ChargerStatus {
    IDLE,
    CHARGING,
    FAULT
}