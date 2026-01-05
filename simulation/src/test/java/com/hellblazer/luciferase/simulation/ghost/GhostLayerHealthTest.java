package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostLayerHealth - VON NC (Neighbor Consistency) metric for overlay health monitoring.
 * <p>
 * NC metric from VON research:
 * - NC = known_neighbors / actual_neighbors
 * - Perfect consistency: NC = 1.0 (all actual neighbors known)
 * - Degradation threshold: NC < 0.9 (missing > 10% of neighbors)
 * - Partition risk: NC < 0.5 (minority partition)
 * <p>
 * Ghost layer implements VON "boundary neighbors" pattern:
 * - When ghost arrives from bubble B, learn about bubble B
 * - Discovery is local and lazy (no global registry)
 * - NC tracks completeness of discovery
 *
 * @author hal.hildebrand
 */
class GhostLayerHealthTest {

    private GhostLayerHealth health;

    @BeforeEach
    void setUp() {
        health = new GhostLayerHealth();
    }

    @Test
    void testInitialState() {
        assertEquals(0, health.getKnownNeighbors(),
                    "Initially no known neighbors");
        assertEquals(0, health.getExpectedNeighbors(),
                    "Initially no expected neighbors");
        assertEquals(1.0f, health.neighborConsistency(),
                    "NC should be 1.0 when no neighbors expected (edge case)");
    }

    @Test
    void testSetExpectedNeighbors() {
        health.setExpectedNeighbors(10);

        assertEquals(10, health.getExpectedNeighbors());
        assertEquals(0, health.getKnownNeighbors());
        assertEquals(0.0f, health.neighborConsistency(),
                    "NC should be 0 when 0/10 neighbors known");
    }

    @Test
    void testRecordGhostSource() {
        health.setExpectedNeighbors(5);

        var bubbleA = UUID.randomUUID();
        health.recordGhostSource(bubbleA);

        assertEquals(1, health.getKnownNeighbors());
        assertEquals(0.2f, health.neighborConsistency(), 0.01f,
                    "NC should be 1/5 = 0.2");
    }

    @Test
    void testMultipleGhostSources() {
        health.setExpectedNeighbors(10);

        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();
        var bubbleC = UUID.randomUUID();

        health.recordGhostSource(bubbleA);
        health.recordGhostSource(bubbleB);
        health.recordGhostSource(bubbleC);

        assertEquals(3, health.getKnownNeighbors());
        assertEquals(0.3f, health.neighborConsistency(), 0.01f,
                    "NC should be 3/10 = 0.3");
    }

    @Test
    void testDuplicateGhostSources() {
        health.setExpectedNeighbors(5);

        var bubbleA = UUID.randomUUID();

        health.recordGhostSource(bubbleA);
        health.recordGhostSource(bubbleA);  // Duplicate
        health.recordGhostSource(bubbleA);  // Duplicate

        assertEquals(1, health.getKnownNeighbors(),
                    "Duplicate sources should not increase count");
        assertEquals(0.2f, health.neighborConsistency(), 0.01f);
    }

    @Test
    void testPerfectConsistency() {
        health.setExpectedNeighbors(5);

        // Discover all 5 expected neighbors
        for (int i = 0; i < 5; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertEquals(5, health.getKnownNeighbors());
        assertEquals(1.0f, health.neighborConsistency(), 0.01f,
                    "NC should be 1.0 when all neighbors known");
    }

    @Test
    void testMissingExpectedGhosts() {
        health.setExpectedNeighbors(10);

        // Discover only 7 out of 10
        for (int i = 0; i < 7; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertEquals(3, health.missingExpectedGhosts(),
                    "Should report 3 missing neighbors");
        assertEquals(0.7f, health.neighborConsistency(), 0.01f);
    }

    @Test
    void testNoDegradationWhenHealthy() {
        health.setExpectedNeighbors(10);

        // Discover 10/10 neighbors (NC = 1.0)
        for (int i = 0; i < 10; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertFalse(health.isDegraded(0.9f),
                   "Should not be degraded when NC = 1.0");
    }

    @Test
    void testDegradationDetection() {
        health.setExpectedNeighbors(10);

        // Discover only 8/10 neighbors (NC = 0.8)
        for (int i = 0; i < 8; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertTrue(health.isDegraded(0.9f),
                  "Should be degraded when NC = 0.8 < threshold 0.9");
        assertFalse(health.isDegraded(0.7f),
                   "Should not be degraded when NC = 0.8 >= threshold 0.7");
    }

    @Test
    void testPartitionRisk() {
        health.setExpectedNeighbors(10);

        // Discover only 4/10 neighbors (NC = 0.4)
        for (int i = 0; i < 4; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertTrue(health.isPartitionRisk(0.5f),
                  "NC < 0.5 indicates partition risk");
        assertEquals(0.4f, health.neighborConsistency(), 0.01f);
    }

    @Test
    void testNoPartitionRiskWhenHealthy() {
        health.setExpectedNeighbors(10);

        // Discover 9/10 neighbors (NC = 0.9)
        for (int i = 0; i < 9; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertFalse(health.isPartitionRisk(0.5f),
                   "NC >= 0.5 means no partition risk");
    }

    @Test
    void testGetKnownSources() {
        health.setExpectedNeighbors(5);

        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();
        var bubbleC = UUID.randomUUID();

        health.recordGhostSource(bubbleA);
        health.recordGhostSource(bubbleB);
        health.recordGhostSource(bubbleC);

        var known = health.getKnownSources();

        assertEquals(3, known.size());
        assertTrue(known.contains(bubbleA));
        assertTrue(known.contains(bubbleB));
        assertTrue(known.contains(bubbleC));
    }

    @Test
    void testGetKnownSourcesIsUnmodifiable() {
        health.recordGhostSource(UUID.randomUUID());

        var known = health.getKnownSources();

        assertThrows(UnsupportedOperationException.class,
                    () -> known.add(UUID.randomUUID()),
                    "Returned set should be unmodifiable");
    }

    @Test
    void testRemoveGhostSource() {
        health.setExpectedNeighbors(5);

        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();

        health.recordGhostSource(bubbleA);
        health.recordGhostSource(bubbleB);

        assertEquals(2, health.getKnownNeighbors());

        health.removeGhostSource(bubbleA);

        assertEquals(1, health.getKnownNeighbors());
        assertFalse(health.getKnownSources().contains(bubbleA));
        assertTrue(health.getKnownSources().contains(bubbleB));
    }

    @Test
    void testRemoveUnknownSource() {
        health.setExpectedNeighbors(5);

        var bubbleA = UUID.randomUUID();
        var unknownBubble = UUID.randomUUID();

        health.recordGhostSource(bubbleA);

        // Remove unknown source (no-op)
        health.removeGhostSource(unknownBubble);

        assertEquals(1, health.getKnownNeighbors(),
                    "Removing unknown source should not change count");
    }

    @Test
    void testUpdateExpectedNeighbors() {
        health.setExpectedNeighbors(10);

        for (int i = 0; i < 5; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertEquals(0.5f, health.neighborConsistency(), 0.01f);

        // Membership view changes: now only 5 expected neighbors
        health.setExpectedNeighbors(5);

        assertEquals(1.0f, health.neighborConsistency(), 0.01f,
                    "NC should update when expected neighbors changes");
    }

    @Test
    void testConcurrentGhostSourceRecording() throws InterruptedException {
        health.setExpectedNeighbors(100);

        int threadCount = 10;
        int sourcesPerThread = 10;

        var threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < sourcesPerThread; j++) {
                    health.recordGhostSource(UUID.randomUUID());
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(100, health.getKnownNeighbors(),
                    "All ghost sources should be recorded (thread-safe)");
        assertEquals(1.0f, health.neighborConsistency(), 0.01f);
    }

    @Test
    void testHealthMetricsSnapshot() {
        health.setExpectedNeighbors(10);

        for (int i = 0; i < 7; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        var snapshot = health.getHealthSnapshot();

        assertEquals(7, snapshot.knownNeighbors());
        assertEquals(10, snapshot.expectedNeighbors());
        assertEquals(3, snapshot.missingNeighbors());
        assertEquals(0.7f, snapshot.neighborConsistency(), 0.01f);
        assertFalse(snapshot.isHealthy());  // NC < 0.9
    }

    @Test
    void testHealthSnapshotWhenHealthy() {
        health.setExpectedNeighbors(10);

        for (int i = 0; i < 10; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        var snapshot = health.getHealthSnapshot();

        assertTrue(snapshot.isHealthy(),
                  "Should be healthy when NC >= 0.9");
    }

    @Test
    void testZeroExpectedNeighborsEdgeCase() {
        health.setExpectedNeighbors(0);

        assertEquals(1.0f, health.neighborConsistency(),
                    "NC should be 1.0 when 0 neighbors expected (edge case)");
        assertFalse(health.isDegraded(0.9f),
                   "Should not be degraded when no neighbors expected");
    }

    @Test
    void testExcessKnownNeighbors() {
        health.setExpectedNeighbors(5);

        // Discover 8 neighbors (more than expected)
        for (int i = 0; i < 8; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        assertEquals(8, health.getKnownNeighbors());
        // NC should be clamped at 1.0 (can't be > 100%)
        assertEquals(1.0f, health.neighborConsistency(), 0.01f,
                    "NC should be clamped at 1.0 when more neighbors discovered than expected");
    }

    @Test
    void testResetState() {
        health.setExpectedNeighbors(10);

        for (int i = 0; i < 5; i++) {
            health.recordGhostSource(UUID.randomUUID());
        }

        health.reset();

        assertEquals(0, health.getKnownNeighbors());
        assertEquals(0, health.getExpectedNeighbors());
        assertTrue(health.getKnownSources().isEmpty());
    }
}
