package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CoherenceSnapshot record.
 * Validates immutability, factory methods, and boundary conditions.
 */
class CoherenceSnapshotTest {

    @Test
    void testEmptyFactory() {
        var empty = CoherenceSnapshot.empty();

        assertEquals(0.0, empty.averageCoherence());
        assertEquals(0.0, empty.minCoherence());
        assertEquals(0.0, empty.maxCoherence());
        assertEquals(0, empty.totalBeams());
        assertEquals(0, empty.maxDepth());
    }

    @Test
    void testValidSnapshot() {
        var snapshot = new CoherenceSnapshot(0.65, 0.2, 0.9, 100, 5);

        assertEquals(0.65, snapshot.averageCoherence(), 0.001);
        assertEquals(0.2, snapshot.minCoherence(), 0.001);
        assertEquals(0.9, snapshot.maxCoherence(), 0.001);
        assertEquals(100, snapshot.totalBeams());
        assertEquals(5, snapshot.maxDepth());
    }

    @Test
    void testCoherenceRange() {
        // Valid range [0.0, 1.0]
        var valid = new CoherenceSnapshot(0.5, 0.0, 1.0, 10, 3);
        assertEquals(0.5, valid.averageCoherence());

        // Edge cases
        var minEdge = new CoherenceSnapshot(0.0, 0.0, 0.0, 1, 1);
        assertEquals(0.0, minEdge.averageCoherence());

        var maxEdge = new CoherenceSnapshot(1.0, 1.0, 1.0, 1, 1);
        assertEquals(1.0, maxEdge.averageCoherence());
    }

    @Test
    void testNegativeValuesRejected() {
        // Negative beams should throw
        assertThrows(IllegalArgumentException.class, () ->
            new CoherenceSnapshot(0.5, 0.0, 1.0, -1, 3)
        );

        // Negative depth should throw
        assertThrows(IllegalArgumentException.class, () ->
            new CoherenceSnapshot(0.5, 0.0, 1.0, 10, -1)
        );
    }

    @Test
    void testMinMaxConsistency() {
        // Min > Max should throw
        assertThrows(IllegalArgumentException.class, () ->
            new CoherenceSnapshot(0.5, 0.9, 0.2, 10, 3)
        );

        // Average outside [min, max] should throw
        assertThrows(IllegalArgumentException.class, () ->
            new CoherenceSnapshot(0.1, 0.5, 0.8, 10, 3)
        );

        assertThrows(IllegalArgumentException.class, () ->
            new CoherenceSnapshot(0.95, 0.2, 0.8, 10, 3)
        );
    }

    @Test
    void testImmutability() {
        var snapshot = new CoherenceSnapshot(0.5, 0.2, 0.8, 50, 4);

        // Verify record is immutable (no setters exist)
        assertEquals(0.5, snapshot.averageCoherence());
        assertEquals(50, snapshot.totalBeams());

        // Creating new instance doesn't affect original
        var snapshot2 = new CoherenceSnapshot(0.7, 0.3, 0.9, 100, 6);
        assertEquals(0.5, snapshot.averageCoherence());
        assertEquals(0.7, snapshot2.averageCoherence());
    }
}
