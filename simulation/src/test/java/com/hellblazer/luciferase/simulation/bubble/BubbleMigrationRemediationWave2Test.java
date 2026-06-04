package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-2 remediation beads on the bubble migration slice:
 * Luciferase-0frcy.5 (BubbleBoundsTracker shrink on move), .15 (keysAreCompatible exact equality).
 */
class BubbleMigrationRemediationWave2Test {

    // ---- Luciferase-0frcy.5: onEntityMoved must allow bounds to shrink ----

    @Test
    void boundsTrackerShrinksWhenEntityMovesInward() {
        var tracker = new BubbleBoundsTracker((byte) 10);

        // Two entities far apart establish a wide centroid.
        tracker.onEntityAdded("a", new Point3f(0f, 0f, 0f));
        tracker.onEntityAdded("b", new Point3f(1000f, 0f, 0f));
        var wideCentroid = tracker.centroid();
        assertNotNull(wideCentroid);
        assertEquals(500.0, wideCentroid.x, 1e-3, "centroid of {0,1000} should be 500");

        // Move 'b' inward toward 'a'. Pre-fix, onEntityMoved only expanded; the centroid
        // would still reflect the stale far position only via positions, but bounds would
        // never shrink. We assert the recalculated centroid tracks the new positions.
        tracker.onEntityMoved("b", new Point3f(1000f, 0f, 0f), new Point3f(0f, 0f, 0f));
        var tightCentroid = tracker.centroid();
        assertNotNull(tightCentroid);
        assertEquals(0.0, tightCentroid.x, 1e-3, "after moving b to origin the centroid must shrink to 0");
    }

    // ---- Luciferase-0frcy.15: keysAreCompatible must use exact equality ----

    @Test
    void keysAreCompatibleRejectsBitAdjacentButDistinctKeys() throws Exception {
        var grid = new TetreeBubbleGrid((byte) 2);
        var tetree = new Tetree<>(new StringEntityIDGenerator(), 100, (byte) 2);
        var dist = new EntityDistribution(grid, tetree);

        var method = EntityDistribution.class.getDeclaredMethod(
                "keysAreCompatible", TetreeKey.class, TetreeKey.class);
        method.setAccessible(true);

        var k0 = TetreeKey.create((byte) 5, 0L, 0L);
        var same = TetreeKey.create((byte) 5, 0L, 0L);
        // Distinct key whose raw getHighBits()/getLowBits() are identical to k0's (both 0) but
        // which is NOT .equals() to k0 — exactly the case the old bit-distance tolerance
        // (highDiff<=8 && lowDiff<=8) silently accepted as "compatible".
        var bitDistanceMatch = TetreeKey.create((byte) 5, 1L, 0L);
        assertNotEquals(k0, bitDistanceMatch, "test premise: keys must be distinct");

        assertTrue((boolean) method.invoke(dist, k0, same),
                   "identical keys must be compatible");
        assertFalse((boolean) method.invoke(dist, k0, bitDistanceMatch),
                    "distinct keys with bit-distance <= old tolerance must NOT be compatible (exact equality)");
    }
}
