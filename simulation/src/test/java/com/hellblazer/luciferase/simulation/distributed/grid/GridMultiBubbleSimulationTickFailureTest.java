/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tick-failure observability + circuit-break behavior (Luciferase-a57pj).
 *
 * <p>{@code tick()} runs on a {@code scheduleAtFixedRate} task and swallows exceptions to keep that task
 * alive. Before this fix a deterministically-failing tick was silently retried every 16ms forever with no
 * failure surface beyond a log line, and {@code tickCount} (a success counter) diverged silently from
 * scheduler invocations. These tests pin the fix: failures are counted, a sustained streak circuit-breaks
 * to a terminal FAILED state (cancelling the task), and a sub-threshold transient self-heals.
 *
 * <p>The failure is injected at {@code tick()}'s outer-try seam — {@code FlockingBehavior.swapVelocityBuffers()},
 * which (unlike the per-entity {@code computeVelocity} path) is NOT wrapped in an inner catch, so a throw
 * there propagates to the tick-level handler under test.
 *
 * @author hal.hildebrand
 */
class GridMultiBubbleSimulationTickFailureTest {

    /** FlockingBehavior whose per-tick buffer swap always throws — every tick fails deterministically. */
    private static final class AlwaysThrowingFlocking extends FlockingBehavior {
        @Override
        public void swapVelocityBuffers() {
            throw new IllegalStateException("test-injected deterministic tick failure");
        }
    }

    /** FlockingBehavior that throws on its first {@code failTimes} ticks, then behaves normally. */
    private static final class FlakyFlocking extends FlockingBehavior {
        private final int failTimes;
        private int calls = 0;  // touched only on the single scheduler thread

        FlakyFlocking(int failTimes) {
            this.failTimes = failTimes;
        }

        @Override
        public void swapVelocityBuffers() {
            if (calls++ < failTimes) {
                throw new IllegalStateException("test-injected transient tick failure #" + calls);
            }
            super.swapVelocityBuffers();
        }
    }

    @Test
    void sustainedTickFailuresCircuitBreakToTerminalFailedState() {
        var config = GridConfiguration.DEFAULT_2X2;
        try (var sim = new GridMultiBubbleSimulation(config, 50, WorldBounds.DEFAULT, new AlwaysThrowingFlocking())) {
            sim.start();

            // Every tick throws → after MAX_CONSECUTIVE_TICK_FAILURES the breaker trips and halts the sim.
            await("circuit-break to FAILED")
                .atMost(Duration.ofSeconds(10))
                .until(sim::isFailed);

            assertFalse(sim.isRunning(), "circuit-break must halt the simulation (running=false)");
            assertFalse(sim.isHealthy(), "a circuit-broken simulation is not healthy");
            assertEquals(0L, sim.getTickCount(),
                    "no tick ever succeeded, so the success counter must stay 0 (it must NOT track invocations)");
            assertTrue(sim.getConsecutiveTickFailures() >= GridMultiBubbleSimulation.MAX_CONSECUTIVE_TICK_FAILURES,
                    "the breaker trips only after MAX_CONSECUTIVE_TICK_FAILURES consecutive failures");
            assertTrue(sim.getTickFailureCount() >= GridMultiBubbleSimulation.MAX_CONSECUTIVE_TICK_FAILURES,
                    "every failing tick must be counted as a lifetime failure");

            // The task was cancelled, so the failure counters stop advancing — confirm it really halted and
            // is not still hot-looping (the silent-retry defect this bead fixes).
            long failuresAtBreak = sim.getTickFailureCount();
            await("no further ticks fire after the breaker trips")
                .during(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(2))
                .until(() -> sim.getTickFailureCount() == failuresAtBreak);
        }
    }

    @Test
    void subThresholdTransientFailuresSelfHealWithoutCircuitBreaking() {
        var transientFailures = GridMultiBubbleSimulation.MAX_CONSECUTIVE_TICK_FAILURES - 1;
        var config = GridConfiguration.DEFAULT_2X2;
        try (var sim = new GridMultiBubbleSimulation(config, 50, WorldBounds.DEFAULT,
                                                     new FlakyFlocking(transientFailures))) {
            sim.start();

            // After the transient streak the next tick succeeds; a success must reset the consecutive counter
            // so the breaker never trips.
            await("a tick eventually succeeds after the transient failures")
                .atMost(Duration.ofSeconds(10))
                .until(() -> sim.getTickCount() > 0);

            assertFalse(sim.isFailed(), "a sub-threshold transient must NOT circuit-break the simulation");
            assertTrue(sim.isHealthy(), "the simulation must remain healthy after self-healing");
            assertEquals(0L, sim.getConsecutiveTickFailures(),
                    "a successful tick must reset the consecutive-failure streak to 0");
            // Exact count is robust here (not flaky): the scheduler is single-threaded and FlakyFlocking.calls
            // is monotone, so every tick after the transient streak succeeds — no further failures can be
            // counted, and tickFailureCount is pinned at exactly transientFailures regardless of how many more
            // successful ticks fire before this assertion runs.
            assertEquals(transientFailures, sim.getTickFailureCount(),
                    "exactly the injected transient failures must be counted (no more, no fewer)");
        }
    }
}
