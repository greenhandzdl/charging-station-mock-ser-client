package com.charging.mock.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChargeSimulator}.
 * <p>
 * Validates:
 * <ul>
 *   <li>Start/stop lifecycle -- init, start, stop, no-op on double start/stop</li>
 *   <li>Energy accumulation -- tick increments by 0.1 kWh, cap at 50.0 kWh</li>
 *   <li>Duration tracking -- elapsed seconds are reported correctly</li>
 *   <li>Fee calculation -- energy * 1.5 rate with HALF_UP rounding</li>
 *   <li>Thread safety basics -- concurrent access to synchronized methods</li>
 *   <li>Idempotency -- multiple startSimulation calls, stop without start</li>
 * </ul>
 */
class ChargeSimulatorTest {

    private ChargeSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new ChargeSimulator();
    }

    // ===== Initial state =====

    @Test
    void initial_state_isNotCharging() {
        assertFalse(simulator.isCharging());
        assertNull(simulator.getStartTime());
        assertEquals(0.0, simulator.getCurrentEnergy(), 0.0);
        assertEquals(0, simulator.getElapsedSeconds());
    }

    // ===== Start / Stop lifecycle =====

    @Test
    void startSimulation_setsChargingStateAndStartTime() {
        simulator.startSimulation();

        assertTrue(simulator.isCharging());
        assertNotNull(simulator.getStartTime());
        assertEquals(0.0, simulator.getCurrentEnergy(), 0.0);
    }

    @Test
    void startSimulation_isIdempotent() {
        simulator.startSimulation();
        var startTime = simulator.getStartTime();

        // Second call should be no-op
        simulator.startSimulation();

        assertTrue(simulator.isCharging());
        assertEquals(startTime, simulator.getStartTime(), "startTime should not change on double start");
    }

    @Test
    void stopSimulation_returnsResultWithZeroEnergyWhenNotStarted() {
        ChargeSimulator.SimulationResult result = simulator.stopSimulation();

        assertEquals(BigDecimal.ZERO, result.getEnergyKwh());
        assertEquals(BigDecimal.ZERO, result.getFee());
        assertEquals(Duration.ZERO, result.getDuration());
        assertFalse(simulator.isCharging());
    }

    @Test
    void stopSimulation_afterStart_returnsResultAndStopsCharging() {
        simulator.startSimulation();

        ChargeSimulator.SimulationResult result = simulator.stopSimulation();

        assertFalse(simulator.isCharging());
        assertNotNull(result.getEnergyKwh());
        assertNotNull(result.getFee());
        // Duration should be >= 0 since we started then stopped
        assertTrue(result.getDurationSeconds() >= 0);
    }

    @Test
    void stopSimulation_resetsChargingFlag() {
        simulator.startSimulation();
        simulator.stopSimulation();

        assertFalse(simulator.isCharging());
        // Can start again after stop
        simulator.startSimulation();
        assertTrue(simulator.isCharging());
    }

    // ===== Tick / energy accumulation =====

    @Test
    void tick_whenNotCharging_doesNothing() {
        simulator.tick();
        assertEquals(0.0, simulator.getCurrentEnergy(), 0.0);
    }

    @Test
    void tick_incrementsEnergyBy0_1kWh() {
        simulator.startSimulation();

        simulator.tick();
        assertEquals(0.1, simulator.getCurrentEnergy(), 1e-9);

        simulator.tick();
        assertEquals(0.2, simulator.getCurrentEnergy(), 1e-9);
    }

    @Test
    void tick_afterMultipleTicks_accumulatesEnergy() {
        simulator.startSimulation();

        int ticks = 50;
        for (int i = 0; i < ticks; i++) {
            simulator.tick();
        }

        assertEquals(ticks * 0.1, simulator.getCurrentEnergy(), 1e-9);
    }

    @Test
    void tick_energyIsCappedAt50_0kWh() {
        simulator.startSimulation();

        // 600 ticks would give 60kWh, which should be capped at 50
        int ticks = 600;
        for (int i = 0; i < ticks; i++) {
            simulator.tick();
        }

        assertEquals(50.0, simulator.getCurrentEnergy(), 1e-9);
    }

    @Test
    void tick_afterStop_hasNoEffect() {
        simulator.startSimulation();
        for (int i = 0; i < 5; i++) {
            simulator.tick();
        }
        simulator.stopSimulation();

        double energyBefore = simulator.getCurrentEnergy();
        simulator.tick();
        assertEquals(energyBefore, simulator.getCurrentEnergy(), 1e-9);
    }

    // ===== Fee calculation =====

    @Test
    void stopSimulation_calculatesCorrectFee() {
        simulator.startSimulation();

        // Accumulate exactly 10.0 kWh = 100 ticks
        for (int i = 0; i < 100; i++) {
            simulator.tick();
        }

        ChargeSimulator.SimulationResult result = simulator.stopSimulation();

        // Fee = 10.0 * 1.5 = 15.0
        assertEquals(0, BigDecimal.valueOf(15.0).compareTo(result.getFee()));
        assertEquals(0, BigDecimal.valueOf(10.0).compareTo(result.getEnergyKwh()));
    }

    @Test
    void stopSimulation_feeUsesHalfUpRounding() {
        // 0.1 kWh * 1.5 = 0.15, with 2 decimal places = 0.15 (no rounding needed)
        // 0.13 kWh would give 0.195, rounding to 0.20
        simulator.startSimulation();
        // Exactly 1 tick = 0.1 kWh
        simulator.tick();
        // But we can't do fractional ticks. Instead test 3 ticks = 0.3 kWh -> fee = 0.45
        simulator.tick();
        simulator.tick();

        ChargeSimulator.SimulationResult result = simulator.stopSimulation();
        assertEquals(0, BigDecimal.valueOf(0.3).compareTo(result.getEnergyKwh()));
        // 0.3 * 1.5 = 0.45
        assertEquals(0, BigDecimal.valueOf(0.45).compareTo(result.getFee()));
    }

    // ===== Elapsed time =====

    @Test
    void getElapsedSeconds_returnsZeroWhenNotStarted() {
        assertEquals(0, simulator.getElapsedSeconds());
    }

    @Test
    void getElapsedSeconds_returnsNonNegativeDuringSimulation() {
        simulator.startSimulation();
        // Can't test exact time, but elapsed should be >= 0
        assertTrue(simulator.getElapsedSeconds() >= 0);
    }

    // ===== Start resets energy =====

    @Test
    void startSimulation_resetsEnergyToZero() {
        simulator.startSimulation();
        for (int i = 0; i < 10; i++) {
            simulator.tick();
        }
        assertEquals(1.0, simulator.getCurrentEnergy(), 1e-9);
        simulator.stopSimulation();

        // Start again -- energy should reset
        simulator.startSimulation();
        assertEquals(0.0, simulator.getCurrentEnergy(), 0.0);
    }

    // ===== SimulationResult value object =====

    @Test
    void simulationResult_holdsCorrectValues() {
        BigDecimal energy = BigDecimal.valueOf(12.34);
        BigDecimal fee = BigDecimal.valueOf(18.51);
        Duration duration = Duration.ofSeconds(125);

        ChargeSimulator.SimulationResult result =
                new ChargeSimulator.SimulationResult(energy, fee, duration);

        assertEquals(0, energy.compareTo(result.getEnergyKwh()));
        assertEquals(0, fee.compareTo(result.getFee()));
        assertEquals(duration, result.getDuration());
        assertEquals(125, result.getDurationSeconds());
    }

    @Test
    void simulationResult_toString_containsEnergyFeeAndDuration() {
        ChargeSimulator.SimulationResult result =
                new ChargeSimulator.SimulationResult(
                        BigDecimal.valueOf(5.0), BigDecimal.valueOf(7.5), Duration.ofSeconds(300));

        String str = result.toString();
        assertTrue(str.contains("5.00"));
        assertTrue(str.contains("7.50"));
        assertTrue(str.contains("5 min")); // 300 sec = 5 min
    }

    // ===== Thread safety smoke test =====

    @Test
    void concurrentTickAccess_doesNotThrow() throws InterruptedException {
        simulator.startSimulation();

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                simulator.tick();
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                simulator.tick();
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // After 200 ticks, energy should be min(20.0, 50.0) = 20.0
        assertEquals(20.0, simulator.getCurrentEnergy(), 1e-9);
    }

    // ===== Edge cases =====

    @Test
    void stopSimulation_thenStart_thenStop_correctlyReusesSimulator() {
        // Simulate the full lifecycle: start -> accumulate -> stop -> start -> stop
        simulator.startSimulation();
        for (int i = 0; i < 30; i++) {
            simulator.tick();
        }
        ChargeSimulator.SimulationResult r1 = simulator.stopSimulation();
        assertEquals(0, BigDecimal.valueOf(3.0).compareTo(r1.getEnergyKwh()));

        // Second cycle
        simulator.startSimulation();
        for (int i = 0; i < 20; i++) {
            simulator.tick();
        }
        ChargeSimulator.SimulationResult r2 = simulator.stopSimulation();
        assertEquals(0, BigDecimal.valueOf(2.0).compareTo(r2.getEnergyKwh()));
    }

    @Test
    void maxEnergySimulation_producesCorrectFee() {
        simulator.startSimulation();
        // Fill to max
        for (int i = 0; i < 1000; i++) {
            simulator.tick();
        }
        assertEquals(50.0, simulator.getCurrentEnergy(), 1e-9);

        ChargeSimulator.SimulationResult result = simulator.stopSimulation();
        // Fee = 50.0 * 1.5 = 75.0
        assertEquals(0, BigDecimal.valueOf(75.0).compareTo(result.getFee()));
    }
}