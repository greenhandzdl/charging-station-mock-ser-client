package com.charging.mock.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *   <li>Fee calculation -- type-based rate * peak/off-peak multiplier with HALF_UP rounding</li>
 *   <li>Thread safety basics -- concurrent access to synchronized methods</li>
 *   <li>Idempotency -- multiple startSimulation calls, stop without start</li>
 * </ul>
 *
 * <p>Fee calculation follows backend pricing:
 * <ul>
 *   <li>FAST base rate: 1.5 元/kWh</li>
 *   <li>SLOW base rate: 0.8 元/kWh</li>
 *   <li>Peak (08:00-22:00): 1.2x multiplier</li>
 *   <li>Off-peak (22:00-08:00): 0.8x multiplier</li>
 * </ul>
 * So fee ranges: FAST=[1.20~1.80]/kWh, SLOW=[0.64~0.96]/kWh depending on time.
 */
class ChargeSimulatorTest {

    // Expected rate range constants (inclusive)
    // FAST: 1.5 * 0.8 = 1.2 (off-peak) to 1.5 * 1.2 = 1.8 (peak)
    private static final BigDecimal FAST_MIN_RATE = new BigDecimal("1.2");
    private static final BigDecimal FAST_MAX_RATE = new BigDecimal("1.8");
    // SLOW: 0.8 * 0.8 = 0.64 to 0.8 * 1.2 = 0.96
    private static final BigDecimal SLOW_MIN_RATE = new BigDecimal("0.64");
    private static final BigDecimal SLOW_MAX_RATE = new BigDecimal("0.96");

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

    @Test
    void initial_chargerType_isFAST() {
        assertEquals("FAST", simulator.getChargerType());
    }

    @Test
    void setChargerType_updatesCorrectly() {
        simulator.setChargerType("SLOW");
        assertEquals("SLOW", simulator.getChargerType());

        simulator.setChargerType(null);
        assertEquals("FAST", simulator.getChargerType(), "null should default to FAST");
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
    void stopSimulation_calculatesFeeWithinExpectedRange() {
        simulator.startSimulation();

        // Accumulate exactly 10.0 kWh = 100 ticks
        for (int i = 0; i < 100; i++) {
            simulator.tick();
        }

        ChargeSimulator.SimulationResult result = simulator.stopSimulation();

        // Energy should be 10.0 kWh
        assertEquals(0, BigDecimal.valueOf(10.0).compareTo(result.getEnergyKwh()));

        // Fee = 10 kWh * effectiveRate
        // effectiveRate depends on time of day:
        //   Peak   (08:00-22:00): FAST 1.5*1.2 = 1.8  → fee 18.0
        //   Off-peak (22:00-08:00): FAST 1.5*0.8 = 1.2 → fee 12.0
        BigDecimal fee = result.getFee();
        BigDecimal rate = fee.divide(result.getEnergyKwh(), 4, RoundingMode.HALF_UP);
        assertTrue(rate.compareTo(FAST_MIN_RATE) >= 0,
                "Rate " + rate + " should be >= " + FAST_MIN_RATE);
        assertTrue(rate.compareTo(FAST_MAX_RATE) <= 0,
                "Rate " + rate + " should be <= " + FAST_MAX_RATE);
    }

    @Test
    void stopSimulation_usesChargerTypeAwareRates() {
        // Test FAST vs SLOW rate for same energy
        simulator.setChargerType("FAST");
        simulator.startSimulation();
        for (int i = 0; i < 20; i++) simulator.tick();
        ChargeSimulator.SimulationResult fastResult = simulator.stopSimulation();

        simulator.setChargerType("SLOW");
        simulator.startSimulation();
        for (int i = 0; i < 20; i++) simulator.tick();
        ChargeSimulator.SimulationResult slowResult = simulator.stopSimulation();

        // Both should have same energy (2.0 kWh)
        assertEquals(0, fastResult.getEnergyKwh().compareTo(slowResult.getEnergyKwh()));

        // FAST fee should be > SLOW fee (1.5 > 0.8 base rates)
        assertTrue(fastResult.getFee().compareTo(slowResult.getFee()) > 0,
                "FAST fee " + fastResult.getFee() + " should be > SLOW fee " + slowResult.getFee());
    }

    @Test
    void stopSimulation_feeUsesHalfUpRounding() {
        simulator.startSimulation();
        // 3 ticks = 0.3 kWh
        simulator.tick();
        simulator.tick();
        simulator.tick();

        ChargeSimulator.SimulationResult result = simulator.stopSimulation();
        assertEquals(0, BigDecimal.valueOf(0.3).compareTo(result.getEnergyKwh()));

        // Fee = 0.3 * effectiveRate. For FAST: 0.3 * [1.2~1.8] = [0.36~0.54]
        // All these should round to 2 decimal places correctly
        BigDecimal fee = result.getFee();
        assertTrue(fee.compareTo(BigDecimal.ZERO) > 0, "Fee should be positive");
        assertEquals(2, fee.scale(), "Fee should have 2 decimal places");

        // Verify the rate is in valid range
        BigDecimal rate = fee.divide(result.getEnergyKwh(), 4, RoundingMode.HALF_UP);
        assertTrue(rate.compareTo(FAST_MIN_RATE) >= 0,
                "Rate " + rate + " should be >= " + FAST_MIN_RATE);
        assertTrue(rate.compareTo(FAST_MAX_RATE) <= 0,
                "Rate " + rate + " should be <= " + FAST_MAX_RATE);
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
        // Use SLOW charger for predictable rate check
        simulator.setChargerType("SLOW");
        simulator.startSimulation();
        for (int i = 0; i < 50; i++) simulator.tick();
        ChargeSimulator.SimulationResult result = simulator.stopSimulation();

        String str = result.toString();
        assertTrue(str.contains("5.00"), "toString should contain energy: " + str);
        assertTrue(str.contains("元"), "toString should contain fee: " + str);
        assertTrue(str.contains("min"), "toString should contain duration: " + str);
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
        assertTrue(r1.getFee().compareTo(BigDecimal.ZERO) > 0);

        // Second cycle
        simulator.startSimulation();
        for (int i = 0; i < 20; i++) {
            simulator.tick();
        }
        ChargeSimulator.SimulationResult r2 = simulator.stopSimulation();
        assertEquals(0, BigDecimal.valueOf(2.0).compareTo(r2.getEnergyKwh()));
        assertTrue(r2.getFee().compareTo(BigDecimal.ZERO) > 0);
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
        assertEquals(0, BigDecimal.valueOf(50.0).compareTo(result.getEnergyKwh()));

        // Fee = 50 * effectiveRate. For FAST: [1.2~1.8] * 50 = [60~90]
        BigDecimal fee = result.getFee();
        assertTrue(fee.compareTo(BigDecimal.valueOf(60)) >= 0,
                "Fee " + fee + " should be >= 60 for 50 kWh FAST");
        assertTrue(fee.compareTo(BigDecimal.valueOf(90)) <= 0,
                "Fee " + fee + " should be <= 90 for 50 kWh FAST");
    }

    @Test
    void differentChargerTypes_haveDifferentRates() {
        // FAST at 10 kWh should cost more than SLOW at same energy
        simulator.setChargerType("FAST");
        simulator.startSimulation();
        for (int i = 0; i < 100; i++) simulator.tick();
        BigDecimal fastFee = simulator.stopSimulation().getFee();

        simulator.setChargerType("SLOW");
        simulator.startSimulation();
        for (int i = 0; i < 100; i++) simulator.tick();
        BigDecimal slowFee = simulator.stopSimulation().getFee();

        assertTrue(fastFee.compareTo(slowFee) > 0,
                "FAST fee " + fastFee + " should be > SLOW fee " + slowFee);
    }
}