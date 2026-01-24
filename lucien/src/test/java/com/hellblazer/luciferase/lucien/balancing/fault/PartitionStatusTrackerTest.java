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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PartitionStatusTracker (P2.1).
 * Validates time-based partition health tracking and history recording.
 */
class PartitionStatusTrackerTest {

    private PartitionStatusTracker tracker;
    private UUID partitionId;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        partitionId = UUID.randomUUID();
        fixedClock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
        tracker = new DefaultPartitionStatusTracker();
        tracker.setClock(fixedClock);
        tracker.start();
    }

    /**
     * T1: Test time since last healthy for new partition.
     * New partition should have no history, duration should reflect time from epoch.
     */
    @Test
    void testTimeSinceLastHealthy_NewPartition() {
        // Mark partition as healthy
        tracker.markHealthy(partitionId);

        // Time since last healthy should be ~0
        var timeSince = tracker.getTimeSinceLastHealthy(partitionId);
        assertNotNull(timeSince);
        assertTrue(timeSince.toMillis() >= 0);
        assertTrue(timeSince.toMillis() <= 100);
    }

    /**
     * T2: Test time since last healthy after failure.
     * After marking healthy, then advancing clock and checking again.
     */
    @Test
    void testTimeSinceLastHealthy_AfterFailure() {
        // Mark as healthy at epoch
        tracker.markHealthy(partitionId);

        // Advance clock by 1000ms
        var advancedClock = Clock.offset(fixedClock, Duration.ofMillis(1000));
        tracker.setClock(advancedClock);

        // Mark as suspected
        var nodeId = UUID.randomUUID();
        tracker.reportHeartbeatFailure(partitionId, nodeId);

        // Time since last healthy should be ~1000ms
        var timeSince = tracker.getTimeSinceLastHealthy(partitionId);
        assertNotNull(timeSince);
        var millis = timeSince.toMillis();
        assertTrue(millis >= 900 && millis <= 1100,
                   "Time since healthy should be ~1000ms, got " + millis);
    }

    /**
     * T3: Test status history records transitions.
     * History should contain records of status changes with timestamps.
     */
    @Test
    void testStatusHistory_RecordsTransitions() {
        // Mark healthy
        tracker.markHealthy(partitionId);

        // Advance clock
        var clock2 = Clock.offset(fixedClock, Duration.ofMillis(500));
        tracker.setClock(clock2);

        // Report failure
        var nodeId = UUID.randomUUID();
        tracker.reportHeartbeatFailure(partitionId, nodeId);

        // Get history
        var history = tracker.getStatusHistory(partitionId);

        assertNotNull(history);
        assertFalse(history.isEmpty(), "History should contain entries");
        assertTrue(history.size() >= 2, "Should have at least healthy and suspected entries");
    }

    /**
     * T4: Test status history is ordered by timestamp.
     * Earlier entries should appear before later entries.
     */
    @Test
    void testStatusHistory_OrderedByTimestamp() {
        var nodeId = UUID.randomUUID();

        // Create sequence of status changes
        tracker.markHealthy(partitionId);

        var clock2 = Clock.offset(fixedClock, Duration.ofMillis(100));
        tracker.setClock(clock2);
        tracker.reportHeartbeatFailure(partitionId, nodeId);

        var clock3 = Clock.offset(fixedClock, Duration.ofMillis(200));
        tracker.setClock(clock3);
        tracker.markHealthy(partitionId);

        var history = tracker.getStatusHistory(partitionId);
        assertNotNull(history);
        assertTrue(history.size() >= 3, "Should have at least 3 history entries");

        // Verify timestamps are in ascending order
        for (int i = 1; i < history.size(); i++) {
            assertTrue(history.get(i).timestamp().isAfter(history.get(i - 1).timestamp()),
                       "History timestamps should be ordered");
        }
    }

    /**
     * T5: Test isStale with threshold within range.
     * Partition not updated should return false if within threshold.
     */
    @Test
    void testIsStale_WithinThreshold() {
        // Mark healthy at epoch
        tracker.markHealthy(partitionId);

        // Advance clock by 500ms
        var advancedClock = Clock.offset(fixedClock, Duration.ofMillis(500));
        tracker.setClock(advancedClock);

        // Check staleness with 1000ms threshold
        var stale = tracker.isStale(partitionId, Duration.ofMillis(1000));
        assertFalse(stale, "Partition should not be stale within threshold");
    }

    /**
     * T6: Test isStale when exceeding threshold.
     * Partition not updated should return true if exceeding threshold.
     */
    @Test
    void testIsStale_ExceedsThreshold() {
        // Mark healthy at epoch
        tracker.markHealthy(partitionId);

        // Advance clock by 1500ms
        var advancedClock = Clock.offset(fixedClock, Duration.ofMillis(1500));
        tracker.setClock(advancedClock);

        // Check staleness with 1000ms threshold
        var stale = tracker.isStale(partitionId, Duration.ofMillis(1000));
        assertTrue(stale, "Partition should be stale after exceeding threshold");
    }

    /**
     * T7: Test clock injection with deterministic time.
     * Setting a fixed clock should make all timestamps deterministic.
     */
    @Test
    void testClockInjection_DeterministicTime() {
        var fixedInstant = Instant.parse("2025-01-23T10:00:00Z");
        var testClock = Clock.fixed(fixedInstant, ZoneId.systemDefault());
        tracker.setClock(testClock);

        // Mark healthy - should use test clock
        tracker.markHealthy(partitionId);

        var history = tracker.getStatusHistory(partitionId);
        assertNotNull(history);
        assertFalse(history.isEmpty());

        // Timestamp should match our fixed clock
        var entry = history.get(0);
        assertEquals(fixedInstant, entry.timestamp(),
                     "Timestamp should use injected clock");
    }

    /**
     * T8: Test transition count accumulates correctly.
     * Each status change should increment the transition count.
     */
    @Test
    void testTransitionCount_AccumulatesCorrectly() {
        var nodeId = UUID.randomUUID();

        // Initial state: 0 transitions
        var count0 = tracker.getTransitionCount(partitionId);
        assertTrue(count0 >= 0);

        // First transition: mark healthy
        tracker.markHealthy(partitionId);
        var count1 = tracker.getTransitionCount(partitionId);

        // Second transition: report failure
        tracker.reportHeartbeatFailure(partitionId, nodeId);
        var count2 = tracker.getTransitionCount(partitionId);

        // Third transition: mark healthy again
        tracker.markHealthy(partitionId);
        var count3 = tracker.getTransitionCount(partitionId);

        // Count should monotonically increase
        assertTrue(count2 > count1, "Count should increase after failure report");
        assertTrue(count3 > count2, "Count should increase after recovery");
    }
}
