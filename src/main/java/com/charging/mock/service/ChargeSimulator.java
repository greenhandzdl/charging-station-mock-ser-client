package com.charging.mock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * ChargeSimulator simulates the energy generation of a physical charger.
 *
 * <p>Uses a timer-based model where each tick (1 second) increases the
 * simulated energy by a fixed increment (0.1 kWh). The simulator records
 * the start time and provides thread-safe access to current energy and
 * duration via synchronized methods.
 *
 * <p>Energy is capped between 1.0 and 50.0 kWh to simulate a reasonable
 * charging range, preventing overflow or unrealistic values.
 */
public class ChargeSimulator {

    /** Energy increment per tick in kWh. */
    private static final double ENERGY_PER_TICK_KWH = 0.1;

    private static final double MIN_ENERGY_KWH = 1.0;
    private static final double MAX_ENERGY_KWH = 50.0;

    private volatile LocalDateTime startTime;
    private volatile boolean charging;

    private double currentEnergyKwh;
    private String currentSimulationId;

    public ChargeSimulator() {
        this.charging = false;
        this.currentEnergyKwh = 0.0;
        this.startTime = null;
        this.currentSimulationId = null;
    }

    public String getCurrentSimulationId() {
        return currentSimulationId;
    }

    public void setCurrentSimulationId(String id) {
        this.currentSimulationId = id;
    }

    /**
     * Reset the simulator to its initial state. Clears energy, simulation ID,
     * and charging flag. Safe to call even if currently charging.
     */
    public synchronized void reset() {
        this.charging = false;
        this.currentEnergyKwh = 0.0;
        this.startTime = null;
        this.currentSimulationId = null;
    }

    /**
     * Start the simulation. Sets the start time and begins energy accumulation.
     * This method is idempotent — calling it when already charging has no effect.
     */
    public synchronized void startSimulation() {
        if (charging) {
            return;
        }
        this.charging = true;
        this.startTime = LocalDateTime.now();
        this.currentEnergyKwh = 0.0;
    }

    /**
     * Stop the simulation and return the result.
     *
     * @return a {@link SimulationResult} containing final energy, fee, and duration
     */
    public synchronized SimulationResult stopSimulation() {
        if (!charging) {
            return new SimulationResult(BigDecimal.ZERO, BigDecimal.ZERO, Duration.ZERO);
        }
        this.charging = false;
        Duration duration = startTime != null ? Duration.between(startTime, LocalDateTime.now()) : Duration.ZERO;
        BigDecimal finalEnergy = BigDecimal.valueOf(currentEnergyKwh)
                .setScale(2, RoundingMode.HALF_UP);

        // Fee calculation: FAST rate = 1.5 元/kWh (standard pricing from UML)
        BigDecimal fee = finalEnergy.multiply(BigDecimal.valueOf(1.5))
                .setScale(2, RoundingMode.HALF_UP);

        return new SimulationResult(finalEnergy, fee, duration);
    }

    /**
     * Advance the simulation by one tick. Called by the UI timer once per second.
     * Adds 0.1 kWh, capped at MAX_ENERGY_KWH.
     */
    public synchronized void tick() {
        if (!charging) {
            return;
        }
        currentEnergyKwh = Math.min(currentEnergyKwh + ENERGY_PER_TICK_KWH, MAX_ENERGY_KWH);
    }

    /**
     * Get the current accumulated energy in kWh (thread-safe).
     */
    public synchronized double getCurrentEnergy() {
        return currentEnergyKwh;
    }

    /**
     * Get the elapsed seconds since simulation started.
     */
    public synchronized long getElapsedSeconds() {
        if (startTime == null) {
            return 0;
        }
        return Duration.between(startTime, LocalDateTime.now()).getSeconds();
    }

    /**
     * Whether the simulation is currently running.
     */
    public boolean isCharging() {
        return charging;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    // ===== Inner class for simulation result =====

    /**
     * Value object holding the result of a completed simulation.
     */
    public static class SimulationResult {
        private final BigDecimal energyKwh;
        private final BigDecimal fee;
        private final Duration duration;

        public SimulationResult(BigDecimal energyKwh, BigDecimal fee, Duration duration) {
            this.energyKwh = energyKwh;
            this.fee = fee;
            this.duration = duration;
        }

        public BigDecimal getEnergyKwh() {
            return energyKwh;
        }

        public BigDecimal getFee() {
            return fee;
        }

        public Duration getDuration() {
            return duration;
        }

        public long getDurationSeconds() {
            return duration.getSeconds();
        }

        @Override
        public String toString() {
            return String.format("Energy: %.2f kWh, Fee: %.2f 元, Duration: %d min",
                    energyKwh, fee, duration.getSeconds() / 60);
        }
    }
}