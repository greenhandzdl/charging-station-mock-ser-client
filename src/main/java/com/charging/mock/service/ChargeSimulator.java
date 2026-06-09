package com.charging.mock.service;

import com.charging.mock.model.ChargerStatus;

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
 *
 * <p>Fee calculation matches backend pricing:
 * <ul>
 *   <li>Base rate: FAST = 1.5 元/kWh, SLOW = 0.8 元/kWh</li>
 *   <li>Peak (08:00-22:00): 1.2x multiplier</li>
 *   <li>Off-peak (22:00-08:00): 0.8x multiplier</li>
 * </ul>
 */
public class ChargeSimulator {

    /** Energy increment per tick in kWh. */
    private static final double ENERGY_PER_TICK_KWH = 0.1;

    private static final double MIN_ENERGY_KWH = 1.0;
    private static final double MAX_ENERGY_KWH = 50.0;

    /** Base rates matching backend StandardPricing. */
    private static final BigDecimal FAST_RATE = new BigDecimal("1.5");
    private static final BigDecimal SLOW_RATE = new BigDecimal("0.8");

    /** Peak hour multiplier matching backend PeakPricing. */
    private static final BigDecimal PEAK_MULTIPLIER = new BigDecimal("1.2");
    /** Off-peak hour multiplier. */
    private static final BigDecimal OFF_PEAK_MULTIPLIER = new BigDecimal("0.8");

    /** Peak hour start (08:00). */
    private static final int PEAK_START_HOUR = 8;
    /** Peak hour end (22:00). */
    private static final int PEAK_END_HOUR = 22;

    /** Default charger type when none specified. */
    private static final String DEFAULT_CHARGER_TYPE = "FAST";

    private volatile LocalDateTime startTime;
    private volatile boolean charging;

    private double currentEnergyKwh;
    private String currentSimulationId;
    private String chargerType;

    public ChargeSimulator() {
        this.charging = false;
        this.currentEnergyKwh = 0.0;
        this.startTime = null;
        this.currentSimulationId = null;
        this.chargerType = DEFAULT_CHARGER_TYPE;
    }

    public String getCurrentSimulationId() {
        return currentSimulationId;
    }

    public void setCurrentSimulationId(String id) {
        this.currentSimulationId = id;
    }

    public String getChargerType() {
        return chargerType;
    }

    public void setChargerType(String chargerType) {
        this.chargerType = chargerType != null ? chargerType : DEFAULT_CHARGER_TYPE;
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
     * Calculate the effective price per kWh based on charger type and current time.
     * Matches backend StandardPricing + PeakPricing logic.
     */
    private BigDecimal calculateEffectiveRate() {
        BigDecimal baseRate = "SLOW".equalsIgnoreCase(chargerType) ? SLOW_RATE : FAST_RATE;
        int hour = LocalDateTime.now().getHour();
        boolean isPeak = hour >= PEAK_START_HOUR && hour < PEAK_END_HOUR;
        BigDecimal multiplier = isPeak ? PEAK_MULTIPLIER : OFF_PEAK_MULTIPLIER;
        return baseRate.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Stop the simulation and return the result.
     * Fee is calculated using backend-aligned pricing (type-based + time-of-day).
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

        // Fee calculation: energy * effectiveRate (type-based + peak/off-peak)
        BigDecimal effectiveRate = calculateEffectiveRate();
        BigDecimal fee = finalEnergy.multiply(effectiveRate)
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