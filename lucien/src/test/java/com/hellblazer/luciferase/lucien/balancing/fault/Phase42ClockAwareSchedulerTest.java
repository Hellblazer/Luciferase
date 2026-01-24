/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4.2 tests for ClockAwareScheduler (Issue #6).
 *
 * Tests verify:
 * - Deterministic tick-based scheduling
 * - Manual tick() advancement for testing
 * - No ScheduledExecutorService (non-deterministic)
 */
class Phase42ClockAwareSchedulerTest {

    private TestClock clock;
    private AtomicInteger taskExecutionCount;
    private ClockAwareScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new TestClock(0); // Start at t=0
        taskExecutionCount = new AtomicInteger(0);

        // Pass clock as LongSupplier via method reference
        scheduler = new ClockAwareScheduler(
            clock::currentTimeMillis,
            1000, // 1000ms interval
            taskExecutionCount::incrementAndGet
        );
    }

    /**
     * Test 1: Deterministic tick behavior.
     *
     * Verifies:
     * - Task executes when interval elapses
     * - Multiple ticks work correctly
     * - Clock advancement controls execution timing
     */
    @Test
    void testDeterministicTick() {
        scheduler.start();

        // Advance clock to t=500ms (< 1000ms interval)
        clock.advance(500);
        boolean executed = scheduler.tick();

        assertThat(executed)
            .as("Task should not execute before interval elapses")
            .isFalse();
        assertThat(taskExecutionCount.get())
            .isEqualTo(0);

        // Advance to t=1000ms (exactly 1 interval)
        clock.advance(500);
        executed = scheduler.tick();

        assertThat(executed)
            .as("Task should execute when interval elapses")
            .isTrue();
        assertThat(taskExecutionCount.get())
            .isEqualTo(1);

        // Advance to t=1500ms (< 2 intervals)
        clock.advance(500);
        executed = scheduler.tick();

        assertThat(executed)
            .as("Task should not execute yet")
            .isFalse();
        assertThat(taskExecutionCount.get())
            .isEqualTo(1);

        // Advance to t=2000ms (2nd interval)
        clock.advance(500);
        executed = scheduler.tick();

        assertThat(executed)
            .as("Task should execute on 2nd interval")
            .isTrue();
        assertThat(taskExecutionCount.get())
            .isEqualTo(2);
    }

    /**
     * Test 2: Multiple tasks with different intervals.
     *
     * Verifies scheduler can handle multiple periodic tasks
     * with different scheduling intervals deterministically.
     */
    @Test
    void testMultipleTasks() {
        AtomicInteger task1Count = new AtomicInteger(0);
        AtomicInteger task2Count = new AtomicInteger(0);

        var scheduler1 = new ClockAwareScheduler(clock::currentTimeMillis, 1000, task1Count::incrementAndGet);
        var scheduler2 = new ClockAwareScheduler(clock::currentTimeMillis, 2000, task2Count::incrementAndGet);

        scheduler1.start();
        scheduler2.start();

        // t=0: No executions
        assertThat(task1Count.get()).isEqualTo(0);
        assertThat(task2Count.get()).isEqualTo(0);

        // t=1000: task1 executes
        clock.advance(1000);
        scheduler1.tick();
        scheduler2.tick();

        assertThat(task1Count.get())
            .as("Task1 should execute after 1000ms")
            .isEqualTo(1);
        assertThat(task2Count.get())
            .as("Task2 should not execute yet (needs 2000ms)")
            .isEqualTo(0);

        // t=2000: both execute
        clock.advance(1000);
        scheduler1.tick();
        scheduler2.tick();

        assertThat(task1Count.get())
            .as("Task1 should execute again at t=2000")
            .isEqualTo(2);
        assertThat(task2Count.get())
            .as("Task2 should execute at t=2000")
            .isEqualTo(1);

        // t=3000: task1 executes, task2 doesn't
        clock.advance(1000);
        scheduler1.tick();
        scheduler2.tick();

        assertThat(task1Count.get())
            .as("Task1 should execute at t=3000")
            .isEqualTo(3);
        assertThat(task2Count.get())
            .as("Task2 should not execute yet")
            .isEqualTo(1);

        // t=4000: both execute
        clock.advance(1000);
        scheduler1.tick();
        scheduler2.tick();

        assertThat(task1Count.get()).isEqualTo(4);
        assertThat(task2Count.get()).isEqualTo(2);
    }
}
