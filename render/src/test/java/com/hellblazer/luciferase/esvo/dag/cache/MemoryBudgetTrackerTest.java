/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.dag.cache;

import com.hellblazer.luciferase.esvo.dag.config.MemoryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for MemoryBudgetTracker.
 *
 * @author hal.hildebrand
 */
class MemoryBudgetTrackerTest {

    private MemoryBudgetTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new MemoryBudgetTracker(1024, MemoryPolicy.STRICT);
    }

    @Test
    void testAllocationWithinBudget() {
        assertTrue(tracker.canAllocate(512));
        tracker.allocate(512);

        assertEquals(512, tracker.getRemainingBytes());
        assertEquals(50.0f, tracker.getUtilizationPercent(), 0.01f);
    }

    @Test
    void testAllocationExceedsBudget_Strict() {
        tracker.allocate(512);

        assertFalse(tracker.canAllocate(600));

        assertThrows(IllegalStateException.class, () -> tracker.allocate(600));
    }

    @Test
    void testAllocationExceedsBudget_Warn() {
        var warnTracker = new MemoryBudgetTracker(1024, MemoryPolicy.WARN);
        warnTracker.allocate(512);

        assertTrue(warnTracker.canAllocate(600)); // Warn policy allows
        warnTracker.allocate(600); // Should log warning but not throw

        assertEquals(1112, warnTracker.getAllocatedBytes());
    }

    @Test
    void testAllocationExceedsBudget_Adaptive() {
        var adaptiveTracker = new MemoryBudgetTracker(1024, MemoryPolicy.ADAPTIVE);
        adaptiveTracker.allocate(512);

        assertTrue(adaptiveTracker.canAllocate(600)); // Adaptive allows
        adaptiveTracker.allocate(600);

        assertEquals(1112, adaptiveTracker.getAllocatedBytes());
    }

    @Test
    void testRelease() {
        tracker.allocate(512);
        assertEquals(512, tracker.getRemainingBytes());

        tracker.release(256);
        assertEquals(768, tracker.getRemainingBytes());
        assertEquals(25.0f, tracker.getUtilizationPercent(), 0.01f);
    }

    @Test
    void testReleaseMoreThanAllocated() {
        tracker.allocate(512);

        assertThrows(IllegalStateException.class, () -> tracker.release(600));
    }

    @Test
    void testGetUtilizationPercent() {
        tracker.allocate(256);
        assertEquals(25.0f, tracker.getUtilizationPercent(), 0.01f);

        tracker.allocate(256);
        assertEquals(50.0f, tracker.getUtilizationPercent(), 0.01f);

        tracker.allocate(512);
        assertEquals(100.0f, tracker.getUtilizationPercent(), 0.01f);
    }

    @Test
    void testGetRemainingBytes() {
        assertEquals(1024, tracker.getRemainingBytes());

        tracker.allocate(100);
        assertEquals(924, tracker.getRemainingBytes());

        tracker.allocate(200);
        assertEquals(724, tracker.getRemainingBytes());

        tracker.release(100);
        assertEquals(824, tracker.getRemainingBytes());
    }

    @Test
    void testGetPolicy() {
        assertEquals(MemoryPolicy.STRICT, tracker.getPolicy());

        var warnTracker = new MemoryBudgetTracker(1024, MemoryPolicy.WARN);
        assertEquals(MemoryPolicy.WARN, warnTracker.getPolicy());
    }

    @Test
    void testZeroBudget() {
        var zeroTracker = new MemoryBudgetTracker(0, MemoryPolicy.STRICT);

        assertFalse(zeroTracker.canAllocate(1));
        assertThrows(IllegalStateException.class, () -> zeroTracker.allocate(1));
    }

    @Test
    void testNegativeBudget() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryBudgetTracker(-1, MemoryPolicy.STRICT));
    }

    @Test
    void testNullPolicy() {
        assertThrows(NullPointerException.class, () -> new MemoryBudgetTracker(1024, null));
    }

    @Test
    void testConcurrentAllocation() throws InterruptedException {
        var concurrentTracker = new MemoryBudgetTracker(10000, MemoryPolicy.STRICT);
        var executor = Executors.newFixedThreadPool(10);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();

        // Concurrent allocations
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                if (concurrentTracker.canAllocate(50)) {
                    concurrentTracker.allocate(50);
                }
            }));
        }

        // Wait for completion
        for (var future : futures) {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                fail("Concurrent operation failed: " + e.getMessage());
            }
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Should not exceed budget
        assertTrue(concurrentTracker.getAllocatedBytes() <= 10000);
    }

    @Test
    void testAllocateZeroBytes() {
        tracker.allocate(0);
        assertEquals(1024, tracker.getRemainingBytes());
        assertEquals(0.0f, tracker.getUtilizationPercent(), 0.01f);
    }

    @Test
    void testReleaseZeroBytes() {
        tracker.allocate(512);
        tracker.release(0);
        assertEquals(512, tracker.getRemainingBytes());
    }

    @Test
    void testAllocateNegativeBytes() {
        assertThrows(IllegalArgumentException.class, () -> tracker.allocate(-100));
    }

    @Test
    void testReleaseNegativeBytes() {
        assertThrows(IllegalArgumentException.class, () -> tracker.release(-100));
    }

    @Test
    void testFullBudgetUtilization() {
        tracker.allocate(1024);
        assertEquals(0, tracker.getRemainingBytes());
        assertEquals(100.0f, tracker.getUtilizationPercent(), 0.01f);

        assertFalse(tracker.canAllocate(1));
    }
}
