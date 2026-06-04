/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.common.time.Clock;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves RealTimeController.tickLoop() sources its deadline-scheduling time from the injected
 * Clock rather than System.nanoTime() (Luciferase-ml7kc clock-injection sweep).
 *
 * @author hal.hildebrand
 */
class RealTimeControllerClockInjectionTest {

    /**
     * A deterministic clock whose nanoTime() auto-advances by a fixed step on each call, so the
     * tick loop's deadline arithmetic always sees the injected source. It counts invocations to
     * prove the controller actually queried it instead of the wall clock.
     */
    private static final class CountingClock implements Clock {
        private final AtomicLong nanos      = new AtomicLong(0);
        private final AtomicLong nanoCalls  = new AtomicLong(0);
        private final long       stepNs;

        CountingClock(long stepNs) {
            this.stepNs = stepNs;
        }

        @Override
        public long currentTimeMillis() {
            return nanos.get() / 1_000_000L;
        }

        @Override
        public long nanoTime() {
            nanoCalls.incrementAndGet();
            return nanos.addAndGet(stepNs);
        }

        long nanoCalls() {
            return nanoCalls.get();
        }
    }

    @Test
    void tickLoopUsesInjectedClockForScheduling() throws InterruptedException {
        // Step the injected clock forward by a full tick period each query, so sleepNs <= 0 and the
        // loop spins quickly without wall-clock sleeps — making the test deterministic and fast.
        var controller = new RealTimeController(UUID.randomUUID(), "clock-injection-test", 100);
        long tickPeriodNs = TimeUnit.NANOSECONDS.convert(1, TimeUnit.SECONDS) / 100;
        var clock = new CountingClock(tickPeriodNs);
        controller.setClock(clock);

        controller.start();
        // Let the loop run a handful of iterations. Each iteration queries the injected clock at
        // least once (the sleep-remainder computation), plus one seed call before the loop.
        Thread.sleep(50);
        controller.stop();

        assertTrue(clock.nanoCalls() > 0,
                   "tickLoop must query the injected Clock.nanoTime(); System.nanoTime() would never touch it");
        assertTrue(controller.getSimulationTime() > 0,
                   "controller should have advanced simulation time while running");
    }
}
