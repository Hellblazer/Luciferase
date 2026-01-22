/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BalanceMetrics.
 *
 * @author hal.hildebrand
 */
public class BalanceMetricsTest {

    private BalanceMetrics metrics;

    @BeforeEach
    public void setUp() {
        metrics = new BalanceMetrics();
    }

    @Test
    public void testInitialState() {
        assertEquals(0, metrics.roundCount(), "Initial round count should be 0");
        assertEquals(Duration.ZERO, metrics.totalTime(), "Initial total time should be 0");
        assertEquals(Duration.ZERO, metrics.averageRoundTime(), "Initial average time should be 0");
        assertEquals(Duration.ZERO, metrics.minRoundTime(), "Initial min time should be 0");
        assertEquals(Duration.ZERO, metrics.maxRoundTime(), "Initial max time should be 0");
        assertEquals(0, metrics.refinementsRequested(), "Initial refinements requested should be 0");
        assertEquals(0, metrics.refinementsApplied(), "Initial refinements applied should be 0");
    }

    @Test
    public void testRecordRound() {
        metrics.recordRound(Duration.ofMillis(100));

        assertEquals(1, metrics.roundCount());
        assertEquals(Duration.ofMillis(100), metrics.totalTime());
        assertEquals(Duration.ofMillis(100), metrics.averageRoundTime());
        assertEquals(Duration.ofMillis(100), metrics.minRoundTime());
        assertEquals(Duration.ofMillis(100), metrics.maxRoundTime());
    }

    @Test
    public void testRecordMultipleRounds() {
        metrics.recordRound(Duration.ofMillis(100));
        metrics.recordRound(Duration.ofMillis(200));
        metrics.recordRound(Duration.ofMillis(150));

        assertEquals(3, metrics.roundCount());
        assertEquals(Duration.ofMillis(450), metrics.totalTime());
        assertEquals(Duration.ofMillis(150), metrics.averageRoundTime());
        assertEquals(Duration.ofMillis(100), metrics.minRoundTime());
        assertEquals(Duration.ofMillis(200), metrics.maxRoundTime());
    }

    @Test
    public void testRecordRefinements() {
        metrics.recordRefinementRequested();
        metrics.recordRefinementRequested();
        metrics.recordRefinementRequested();

        assertEquals(3, metrics.refinementsRequested());
        assertEquals(0, metrics.refinementsApplied());
        assertEquals(0.0, metrics.refinementApplicationRate(), 0.001);

        metrics.recordRefinementApplied();
        metrics.recordRefinementApplied();

        assertEquals(3, metrics.refinementsRequested());
        assertEquals(2, metrics.refinementsApplied());
        assertEquals(2.0 / 3.0, metrics.refinementApplicationRate(), 0.001);
    }

    @Test
    public void testRefinementApplicationRateZeroRequests() {
        // When no refinements requested, rate should be 0
        assertEquals(0.0, metrics.refinementApplicationRate(), 0.001);

        // Even with applications
        metrics.recordRefinementApplied();
        assertEquals(0.0, metrics.refinementApplicationRate(), 0.001);
    }

    @Test
    public void testReset() {
        // Record some metrics
        metrics.recordRound(Duration.ofMillis(100));
        metrics.recordRefinementRequested();
        metrics.recordRefinementApplied();

        // Verify non-zero state
        assertTrue(metrics.roundCount() > 0);
        assertTrue(metrics.refinementsRequested() > 0);

        // Reset
        metrics.reset();

        // Verify zero state
        assertEquals(0, metrics.roundCount());
        assertEquals(Duration.ZERO, metrics.totalTime());
        assertEquals(0, metrics.refinementsRequested());
        assertEquals(0, metrics.refinementsApplied());
    }

    @Test
    public void testSnapshot() {
        metrics.recordRound(Duration.ofMillis(100));
        metrics.recordRound(Duration.ofMillis(200));
        metrics.recordRefinementRequested();
        metrics.recordRefinementApplied();

        var snapshot = metrics.snapshot();

        assertNotNull(snapshot);
        assertEquals(2, snapshot.roundCount());
        assertEquals(Duration.ofMillis(300), snapshot.totalTime());
        assertEquals(Duration.ofMillis(150), snapshot.averageRoundTime());
        assertEquals(Duration.ofMillis(100), snapshot.minRoundTime());
        assertEquals(Duration.ofMillis(200), snapshot.maxRoundTime());
        assertEquals(1, snapshot.refinementsRequested());
        assertEquals(1, snapshot.refinementsApplied());
        assertEquals(1.0, snapshot.refinementApplicationRate(), 0.001);
    }

    @Test
    public void testSnapshotImmutable() {
        var snapshot = metrics.snapshot();
        var initialRounds = snapshot.roundCount();

        // Record more metrics
        metrics.recordRound(Duration.ofMillis(100));

        // Snapshot should not change
        assertEquals(initialRounds, snapshot.roundCount());

        // New snapshot should reflect changes
        var newSnapshot = metrics.snapshot();
        assertEquals(initialRounds + 1, newSnapshot.roundCount());
    }

    @Test
    public void testThreadSafety() throws InterruptedException {
        var threadCount = 10;
        var operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        // Submit concurrent updates
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        metrics.recordRound(Duration.ofMillis(10));
                        metrics.recordRefinementRequested();
                        if (j % 2 == 0) {
                            metrics.recordRefinementApplied();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for completion
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // Verify counts
        assertEquals(threadCount * operationsPerThread, metrics.roundCount());
        assertEquals(threadCount * operationsPerThread, metrics.refinementsRequested());
        assertEquals(threadCount * operationsPerThread / 2, metrics.refinementsApplied());

        executor.shutdown();
    }

    @Test
    public void testToString() {
        metrics.recordRound(Duration.ofMillis(100));
        var str = metrics.toString();

        assertNotNull(str);
        assertTrue(str.contains("BalanceMetrics"));
        assertTrue(str.contains("rounds"));
    }
}
