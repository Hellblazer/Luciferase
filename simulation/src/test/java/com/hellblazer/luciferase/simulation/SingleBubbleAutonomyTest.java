/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7A Test: Single Bubble Autonomous Execution
 *
 * Validates that a single bubble can run autonomously on simulation time without
 * external synchronization. Tests determinism, simulation time advancement, and
 * entity lifecycle management.
 *
 * SUCCESS CRITERIA:
 * - Single bubble executes without BucketScheduler coordination
 * - RealTimeController advances simulation time correctly
 * - Lamport clock advances monotonically
 * - Autonomous execution (no external ticking required)
 *
 * @author hal.hildebrand
 */
class SingleBubbleAutonomyTest {

    private static final String BUBBLE_NAME = "test-bubble";

    /**
     * Test: RealTimeController initializes with zero time.
     */
    @Test
    void testInitialization() {
        var bubbleId = UUID.randomUUID();
        var controller = new RealTimeController(bubbleId, BUBBLE_NAME);

        assertEquals(0L, controller.getSimulationTime(), "Initial simulation time should be 0");
        assertEquals(0L, controller.getLamportClock(), "Initial Lamport clock should be 0");
        assertEquals(bubbleId, controller.getBubbleId(), "Bubble ID mismatch");
        assertEquals(BUBBLE_NAME, controller.getName(), "Controller name mismatch");
        assertFalse(controller.isRunning(), "Controller should not be running initially");
    }

    /**
     * Test: RealTimeController advances simulation time autonomously.
     */
    @Test
    void testAutonomousTicking() throws InterruptedException {
        var bubbleId = UUID.randomUUID();
        var controller = new RealTimeController(bubbleId, BUBBLE_NAME, 1000); // 1000 Hz (1ms per tick)

        controller.start();
        assertTrue(controller.isRunning(), "Controller should be running after start");

        // Wait for ticks to accumulate
        Thread.sleep(100); // Wait 100ms -> expect ~100 ticks at 1000 Hz

        var simulationTime = controller.getSimulationTime();
        var lamportClock = controller.getLamportClock();

        controller.stop();
        assertFalse(controller.isRunning(), "Controller should not be running after stop");

        // Assert: Time advanced
        assertTrue(simulationTime > 0, "Simulation time should have advanced: " + simulationTime);
        assertTrue(lamportClock > 0, "Lamport clock should have advanced: " + lamportClock);
        assertEquals(simulationTime, lamportClock, "Simulation time and Lamport clock should be equal");

        // Assert: Approximately correct number of ticks (allow 20% variance for timing jitter)
        assertTrue(simulationTime >= 80, "Expected at least 80 ticks, got " + simulationTime);
        assertTrue(simulationTime <= 120, "Expected at most 120 ticks, got " + simulationTime);
    }

    /**
     * Test: Simulation time is monotonically increasing.
     */
    @Test
    void testMonotonicity() throws InterruptedException {
        var bubbleId = UUID.randomUUID();
        var controller = new RealTimeController(bubbleId, BUBBLE_NAME, 1000);

        controller.start();

        var prevTime = controller.getSimulationTime();
        var prevClock = controller.getLamportClock();

        // Sample time repeatedly
        for (int i = 0; i < 10; i++) {
            Thread.sleep(10); // Wait 10ms
            var currentTime = controller.getSimulationTime();
            var currentClock = controller.getLamportClock();

            assertTrue(currentTime >= prevTime, "Simulation time must be monotonically increasing");
            assertTrue(currentClock >= prevClock, "Lamport clock must be monotonically increasing");

            prevTime = currentTime;
            prevClock = currentClock;
        }

        controller.stop();
    }

    /**
     * Test: Multiple controllers run independently.
     */
    @Test
    void testIndependentControllers() throws InterruptedException {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        var controller1 = new RealTimeController(bubble1, "bubble-1", 1000);
        var controller2 = new RealTimeController(bubble2, "bubble-2", 500); // Half the speed

        controller1.start();
        controller2.start();

        Thread.sleep(200); // Wait 200ms

        var time1 = controller1.getSimulationTime();
        var time2 = controller2.getSimulationTime();

        controller1.stop();
        controller2.stop();

        // Assert: Both advanced
        assertTrue(time1 > 0, "Controller 1 should have advanced");
        assertTrue(time2 > 0, "Controller 2 should have advanced");

        // Assert: Controller 1 ticked approximately twice as fast as controller 2
        var ratio = (double) time1 / time2;
        assertTrue(ratio >= 1.5 && ratio <= 2.5,
                 "Expected ~2x ratio, got " + ratio + " (time1=" + time1 + ", time2=" + time2 + ")");
    }

    /**
     * Test: Lamport clock update on remote event.
     */
    @Test
    void testLamportClockUpdate() {
        var bubbleId = UUID.randomUUID();
        var controller = new RealTimeController(bubbleId, BUBBLE_NAME);

        controller.start();

        // Wait for some ticks
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var localClock = controller.getLamportClock();

        // Simulate receiving remote event with higher clock
        var remoteClock = localClock + 100;
        controller.updateLamportClock(remoteClock);

        var updatedClock = controller.getLamportClock();

        controller.stop();

        // Assert: Lamport clock jumped ahead
        assertTrue(updatedClock > remoteClock,
                 "Lamport clock should be max(local, remote) + 1: expected >" + remoteClock + ", got " + updatedClock);
    }
}
