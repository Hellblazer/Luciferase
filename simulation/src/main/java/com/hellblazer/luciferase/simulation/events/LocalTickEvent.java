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

package com.hellblazer.luciferase.simulation.events;

import java.util.UUID;

/**
 * LocalTickEvent - Simulation Tick Event (Phase 7A)
 *
 * Emitted by RealTimeController on each simulation tick.
 * Triggers entity lifecycle updates within a bubble.
 *
 * PAYLOAD:
 * - bubbleId: UUID of the bubble emitting this tick
 * - simulationTime: Current simulation time (tick count)
 * - lamportClock: Lamport clock for event ordering
 *
 * PHASE 7A: Simple data class for tick coordination.
 * PHASE 7B: Will extend Prime-Mover Event and be serialized for cross-bubble delivery via Delos.
 *
 * @author hal.hildebrand
 */
public class LocalTickEvent {

    private UUID bubbleId;
    private long simulationTime;
    private long lamportClock;

    public LocalTickEvent() {
    }

    public LocalTickEvent(UUID bubbleId, long simulationTime, long lamportClock) {
        this.bubbleId = bubbleId;
        this.simulationTime = simulationTime;
        this.lamportClock = lamportClock;
    }

    /**
     * Get the bubble ID that emitted this tick event.
     *
     * @return UUID of the source bubble
     */
    public UUID getBubbleId() {
        return bubbleId;
    }

    /**
     * Set the bubble ID.
     *
     * @param bubbleId UUID of the source bubble
     */
    public void setBubbleId(UUID bubbleId) {
        this.bubbleId = bubbleId;
    }

    /**
     * Get current simulation time.
     * This is the logical tick count for the bubble, independent of wall-clock time.
     *
     * @return Current simulation time (tick count since bubble start)
     */
    public long getSimulationTime() {
        return simulationTime;
    }

    /**
     * Set simulation time.
     *
     * @param simulationTime Tick count
     */
    public void setSimulationTime(long simulationTime) {
        this.simulationTime = simulationTime;
    }

    /**
     * Get Lamport clock value.
     * Used for event ordering across distributed bubbles.
     *
     * @return Lamport clock value
     */
    public long getLamportClock() {
        return lamportClock;
    }

    /**
     * Set Lamport clock value.
     *
     * @param lamportClock Lamport clock value
     */
    public void setLamportClock(long lamportClock) {
        this.lamportClock = lamportClock;
    }

    @Override
    public String toString() {
        return String.format("LocalTickEvent{bubbleId=%s, simTime=%d, lamportClock=%d}",
                           bubbleId, simulationTime, lamportClock);
    }
}
