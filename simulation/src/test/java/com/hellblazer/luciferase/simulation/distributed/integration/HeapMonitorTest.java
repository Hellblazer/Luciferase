/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for HeapMonitor - memory leak detection.
 * Disabled in CI: All tests depend on GC behavior and timing which varies in CI environments.
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "All HeapMonitor tests depend on GC behavior and timing which varies in CI")
class HeapMonitorTest {

    private HeapMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new HeapMonitor();
    }

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.stop();
        }
    }

    @Test
    void testStartStop() {
        monitor.start(100);
        assertTrue(monitor.isRunning(), "Monitor should be running after start");

        monitor.stop();
        assertFalse(monitor.isRunning(), "Monitor should not be running after stop");
    }

    @Test
    void testSnapshotCollection() throws InterruptedException {
        monitor.start(50);
        Thread.sleep(200);

        var snapshots = monitor.getSnapshots();
        assertTrue(snapshots.size() >= 3, "Should have collected at least 3 snapshots in 200ms");
    }

    @Test
    void testPeakMemory() throws InterruptedException {
        monitor.start(50);
        Thread.sleep(100);

        var peakMemory = monitor.getPeakMemory();
        assertTrue(peakMemory > 0, "Peak memory should be positive");
    }

    @Test
    void testCurrentMemory() {
        monitor.start(50);

        var currentMemory = monitor.getCurrentMemory();
        assertTrue(currentMemory > 0, "Current memory should be positive");
    }

    @Test
    void testStableMemory() throws InterruptedException {
        monitor.start(50);
        Thread.sleep(300);

        var growthRate = monitor.getGrowthRate();
        assertFalse(monitor.hasLeak(1_000_000), "Should not detect leak with stable memory");
    }

    @Test
    void testGrowingMemory() throws InterruptedException {
        // Stabilize heap before starting
        System.gc();
        Thread.sleep(100);

        // Allocate baseline to hold
        var baseline = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            baseline.add(new byte[1024 * 1024]); // 5MB baseline
        }

        monitor.start(100); // Longer interval for more stable regression
        Thread.sleep(200); // Let monitor establish baseline

        var leakList = new ArrayList<byte[]>();
        for (int i = 0; i < 20; i++) {
            leakList.add(new byte[10 * 1024 * 1024]); // 10MB per iteration for very strong signal
            Thread.sleep(100);
        }

        var growthRate = monitor.getGrowthRate();
        assertTrue(growthRate > 0, "Growth rate should be positive with growing memory");
        assertTrue(monitor.hasLeak(100_000), "Should detect leak with growing memory");
    }

    @Test
    void testGrowthRate() throws InterruptedException {
        // Stabilize heap before starting
        System.gc();
        Thread.sleep(100);

        // Allocate baseline to hold
        var baseline = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            baseline.add(new byte[1024 * 1024]); // 5MB baseline
        }

        monitor.start(100); // Longer interval for more stable regression
        Thread.sleep(200); // Let monitor establish baseline

        var leakList = new ArrayList<byte[]>();
        for (int i = 0; i < 15; i++) {
            leakList.add(new byte[10 * 1024 * 1024]); // 10MB per iteration for very strong signal
            Thread.sleep(100);
        }

        var growthRate = monitor.getGrowthRate();
        assertTrue(growthRate > 0, "Growth rate should be positive");
    }

    @Test
    void testMultipleSnapshots() throws InterruptedException {
        monitor.start(50);
        Thread.sleep(250);

        var snapshots = monitor.getSnapshots();
        assertTrue(snapshots.size() >= 4, "Should have multiple snapshots");

        for (int i = 1; i < snapshots.size(); i++) {
            var prev = snapshots.get(i - 1);
            var curr = snapshots.get(i);
            assertTrue(curr.timestamp() >= prev.timestamp(),
                       "Snapshots should be in chronological order");
        }
    }

    @Test
    void testGetSnapshots() throws InterruptedException {
        monitor.start(50);
        Thread.sleep(150);

        var snapshots = monitor.getSnapshots();
        assertFalse(snapshots.isEmpty(), "Should have collected snapshots");

        for (var snapshot : snapshots) {
            assertTrue(snapshot.timestamp() > 0, "Snapshot timestamp should be valid");
            assertTrue(snapshot.heapUsage() >= 0, "Heap usage should be non-negative");
        }
    }

    @Test
    void testCleanUp() throws InterruptedException {
        monitor.start(50);
        Thread.sleep(100);

        assertTrue(monitor.getSnapshots().size() > 0, "Should have snapshots before cleanup");

        monitor.stop();

        assertFalse(monitor.isRunning(), "Monitor should be stopped");
    }
}
