package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AffinityTracker - entity affinity metric calculation.
 * <p>
 * Affinity formula: internal / (internal + external)
 * - Core: > 0.8 (strong internal affinity)
 * - Boundary: 0.5 - 0.8 (balanced)
 * - Drifting: < 0.5 (weak internal affinity, candidate for migration)
 * - Edge case: 0/0 = 0.5 (boundary classification, no data)
 *
 * @author hal.hildebrand
 */
class AffinityTrackerTest {

    @Test
    void testAffinityCalculation() {
        // Pure internal interactions (affinity = 1.0)
        var tracker = new AffinityTracker(10, 0);
        assertEquals(1.0f, tracker.affinity(), 0.001f);

        // Pure external interactions (affinity = 0.0)
        tracker = new AffinityTracker(0, 10);
        assertEquals(0.0f, tracker.affinity(), 0.001f);

        // Balanced interactions (affinity = 0.5)
        tracker = new AffinityTracker(5, 5);
        assertEquals(0.5f, tracker.affinity(), 0.001f);

        // Core entity (affinity = 0.9)
        tracker = new AffinityTracker(9, 1);
        assertEquals(0.9f, tracker.affinity(), 0.001f);

        // Drifting entity (affinity = 0.3)
        tracker = new AffinityTracker(3, 7);
        assertEquals(0.3f, tracker.affinity(), 0.001f);
    }

    @Test
    void testEdgeCaseZeroInteractions() {
        // Edge case: no interactions recorded yet
        // Should return 0.5 (boundary classification, uncertain)
        var tracker = new AffinityTracker(0, 0);
        assertEquals(0.5f, tracker.affinity(), 0.001f,
                    "0/0 should return 0.5 (boundary classification)");
    }

    @Test
    void testCoreClassification() {
        // Core: affinity > 0.8
        assertTrue(new AffinityTracker(9, 1).isCore());
        assertTrue(new AffinityTracker(10, 0).isCore());
        assertTrue(new AffinityTracker(85, 15).isCore());

        // Not core (boundary)
        assertFalse(new AffinityTracker(8, 2).isCore()); // 0.8 exactly
        assertFalse(new AffinityTracker(7, 3).isCore()); // 0.7

        // Not core (drifting)
        assertFalse(new AffinityTracker(3, 7).isCore());
    }

    @Test
    void testBoundaryClassification() {
        // Boundary: 0.5 <= affinity <= 0.8
        assertTrue(new AffinityTracker(5, 5).isBoundary()); // 0.5
        assertTrue(new AffinityTracker(6, 4).isBoundary()); // 0.6
        assertTrue(new AffinityTracker(7, 3).isBoundary()); // 0.7
        assertTrue(new AffinityTracker(8, 2).isBoundary()); // 0.8

        // Edge case: 0/0 = 0.5 (boundary)
        assertTrue(new AffinityTracker(0, 0).isBoundary());

        // Not boundary (core)
        assertFalse(new AffinityTracker(9, 1).isBoundary()); // 0.9

        // Not boundary (drifting)
        assertFalse(new AffinityTracker(4, 6).isBoundary()); // 0.4
    }

    @Test
    void testDriftingClassification() {
        // Drifting: affinity < 0.5
        assertTrue(new AffinityTracker(4, 6).isDrifting()); // 0.4
        assertTrue(new AffinityTracker(3, 7).isDrifting()); // 0.3
        assertTrue(new AffinityTracker(0, 10).isDrifting()); // 0.0

        // Not drifting (boundary)
        assertFalse(new AffinityTracker(5, 5).isDrifting()); // 0.5 exactly
        assertFalse(new AffinityTracker(0, 0).isDrifting()); // 0.5 (edge case)

        // Not drifting (core)
        assertFalse(new AffinityTracker(9, 1).isDrifting());
    }

    @Test
    void testRecordInternal() {
        var tracker = new AffinityTracker(5, 5);
        assertEquals(0.5f, tracker.affinity(), 0.001f);

        // Record internal interaction
        var updated = tracker.recordInternal();
        assertEquals(6, updated.internal());
        assertEquals(5, updated.external());
        assertEquals(6.0f / 11.0f, updated.affinity(), 0.001f);

        // Original unchanged (immutable record)
        assertEquals(5, tracker.internal());
        assertEquals(5, tracker.external());
    }

    @Test
    void testRecordExternal() {
        var tracker = new AffinityTracker(5, 5);
        assertEquals(0.5f, tracker.affinity(), 0.001f);

        // Record external interaction
        var updated = tracker.recordExternal();
        assertEquals(5, updated.internal());
        assertEquals(6, updated.external());
        assertEquals(5.0f / 11.0f, updated.affinity(), 0.001f);

        // Original unchanged (immutable record)
        assertEquals(5, tracker.internal());
        assertEquals(5, tracker.external());
    }

    @Test
    void testAffinityProgression() {
        // Entity starts with no data (boundary)
        var tracker = new AffinityTracker(0, 0);
        assertTrue(tracker.isBoundary());

        // Records internal interactions, becomes core
        tracker = tracker.recordInternal()
                        .recordInternal()
                        .recordInternal()
                        .recordInternal()
                        .recordInternal(); // 5 internal, 0 external

        assertTrue(tracker.isCore());
        assertEquals(1.0f, tracker.affinity(), 0.001f);

        // Records external interactions, shifts to boundary
        tracker = tracker.recordExternal()
                        .recordExternal(); // 5 internal, 2 external

        assertTrue(tracker.isBoundary());
        assertEquals(5.0f / 7.0f, tracker.affinity(), 0.001f);

        // More external interactions, becomes drifting
        tracker = tracker.recordExternal()
                        .recordExternal()
                        .recordExternal()
                        .recordExternal()
                        .recordExternal(); // 5 internal, 7 external

        assertTrue(tracker.isDrifting());
        assertEquals(5.0f / 12.0f, tracker.affinity(), 0.001f);
    }

    @Test
    void testClassificationMutuallyExclusive() {
        // Verify exactly one classification is true for various affinity values
        var testCases = new AffinityTracker[]{
            new AffinityTracker(0, 0),   // 0.5 - boundary
            new AffinityTracker(0, 10),  // 0.0 - drifting
            new AffinityTracker(10, 0),  // 1.0 - core
            new AffinityTracker(3, 7),   // 0.3 - drifting
            new AffinityTracker(6, 4),   // 0.6 - boundary
            new AffinityTracker(9, 1),   // 0.9 - core
        };

        for (var tracker : testCases) {
            int classificationCount = 0;
            if (tracker.isDrifting()) classificationCount++;
            if (tracker.isBoundary()) classificationCount++;
            if (tracker.isCore()) classificationCount++;

            assertEquals(1, classificationCount,
                        "Exactly one classification should be true for affinity=" + tracker.affinity());
        }
    }
}
