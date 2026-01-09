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

package com.hellblazer.luciferase.simulation.bubble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RealTimeController - Autonomous Bubble Time Manager (Phase 7A)
 *
 * Maintains bubble-local simulation time independent of other bubbles.
 * Drives entity updates via Prime-Mover @OnTick lifecycle.
 * Emits LocalTickEvent on each simulation tick for entity coordination.
 *
 * KEY PROPERTIES:
 * - Simulation time: Logical clock, incremented once per tick
 * - Lamport clock: Event ordering across distributed bubbles
 * - Autonomous: No dependency on external synchronization (BucketScheduler)
 * - Deterministic: Same seed produces identical time sequences
 *
 * USAGE:
 * <pre>
 *   var controller = new RealTimeController(bubbleId, "bubble-0");
 *   controller.start();  // Begin autonomous ticking
 *   // ... simulation runs ...
 *   controller.stop();   // Halt simulation
 * </pre>
 *
 * NOTE: Phase 7A version - not yet a Prime-Mover @Entity.
 * Will be converted to @Entity in Phase 7B when integrating with event system.
 *
 * @author hal.hildebrand
 */
public class RealTimeController {

    private static final Logger log = LoggerFactory.getLogger(RealTimeController.class);

    private final UUID         bubbleId;
    private final String       name;
    private final AtomicLong   simulationTime;
    private final AtomicLong   lamportClock;
    private final AtomicBoolean running;
    private final long         tickPeriodNs;
    private Thread            tickThread;

    /**
     * Create a RealTimeController for a bubble.
     *
     * @param bubbleId Unique identifier for this bubble
     * @param name     Human-readable name for logging
     */
    public RealTimeController(UUID bubbleId, String name) {
        this(bubbleId, name, 100); // Default to 100 Hz (10ms per tick)
    }

    /**
     * Create a RealTimeController with custom tick rate.
     *
     * @param bubbleId Unique identifier for this bubble
     * @param name     Human-readable name for logging
     * @param tickRate Ticks per second
     */
    public RealTimeController(UUID bubbleId, String name, int tickRate) {
        this.bubbleId = bubbleId;
        this.name = name;
        this.simulationTime = new AtomicLong(0L);
        this.lamportClock = new AtomicLong(0L);
        this.running = new AtomicBoolean(false);
        this.tickPeriodNs = TimeUnit.NANOSECONDS.convert(1, TimeUnit.SECONDS) / tickRate;
        this.tickThread = null;
    }

    /**
     * Get current simulation time for this bubble.
     * Incremented once per tick, independent of other bubbles.
     *
     * @return Current simulation time (tick count since start)
     */
    
    public long getSimulationTime() {
        return simulationTime.get();
    }

    /**
     * Get current Lamport clock value.
     * Used for event ordering across bubbles.
     *
     * @return Current Lamport clock value
     */
    
    public long getLamportClock() {
        return lamportClock.get();
    }

    /**
     * Get bubble ID.
     *
     * @return UUID of this bubble
     */
    
    public UUID getBubbleId() {
        return bubbleId;
    }

    /**
     * Get controller name.
     *
     * @return Human-readable name
     */
    
    public String getName() {
        return name;
    }

    /**
     * Check if controller is running.
     *
     * @return true if controller is actively ticking
     */
    
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Start autonomous ticking.
     * Initializes simulation time to 0 and begins tick loop.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            simulationTime.set(0L);
            lamportClock.set(0L);
            log.info("RealTimeController started: bubble={}, name={}", bubbleId, name);

            // Start the tick thread
            tickThread = new Thread(this::tickLoop, name + "-tick");
            tickThread.setDaemon(false);
            tickThread.start();
        }
    }

    /**
     * Stop autonomous ticking.
     * Halts tick loop and preserves final simulation time.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (tickThread != null) {
                try {
                    tickThread.join(1000); // Wait up to 1 second for thread to stop
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("RealTimeController stopped: bubble={}, name={}, finalTime={}, finalClock={}",
                   bubbleId, name, simulationTime.get(), lamportClock.get());
        }
    }

    /**
     * Simulation tick loop.
     *
     * Advances simulation time and Lamport clock.
     * Emits LocalTickEvent for entity coordination.
     * Runs autonomously in dedicated thread.
     *
     * This is the core autonomous tick mechanism - no external synchronization required.
     */
    private void tickLoop() {
        while (running.get()) {
            var currentSimTime = simulationTime.incrementAndGet();
            var currentLamportClock = lamportClock.incrementAndGet();

            // Emit local tick event for entity updates
            emitLocalTickEvent(currentSimTime, currentLamportClock);

            if (currentSimTime % 100 == 0) {
                log.debug("Tick: bubble={}, simTime={}, lamportClock={}", bubbleId, currentSimTime, currentLamportClock);
            }

            // Sleep for tick period
            try {
                TimeUnit.NANOSECONDS.sleep(tickPeriodNs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.debug("Tick loop exited: bubble={}, finalTime={}", bubbleId, simulationTime.get());
    }

    /**
     * Emit LocalTickEvent for entity coordination.
     *
     * Note: For Phase 7A, event emission is simplified since we're testing single-bubble autonomy.
     * Phase 7B will implement full event delivery via Delos.
     *
     * @param simTime      Current simulation time
     * @param lamportClock Current Lamport clock
     */
    private void emitLocalTickEvent(long simTime, long lamportClock) {
        // Phase 7A: Event emission placeholder
        // Full implementation in Phase 7B with Delos event transport
        // For now, this is a no-op - entity updates will be driven by entity lifecycle
    }

    /**
     * Update Lamport clock upon receiving remote event.
     * Applies: localClock = max(localClock, remoteClock) + 1
     *
     * @param remoteClock Lamport clock from remote event
     */
    
    public void updateLamportClock(long remoteClock) {
        lamportClock.updateAndGet(current -> Math.max(current, remoteClock) + 1);
    }

    @Override
    public String toString() {
        return String.format("RealTimeController{bubble=%s, name=%s, simTime=%d, lamportClock=%d, running=%s}",
                           bubbleId, name, simulationTime.get(), lamportClock.get(), running.get());
    }
}
