package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.11: GhostStateManager must read the bubble's CURRENT bounds
 * (via a supplier) on each dead-reckoning clamp, not a stale snapshot captured at construction.
 */
class GhostStateManagerLiveBoundsRemediationWave2Test {

    @Test
    void supplierBoundsAreReReadNotSnapshotted() {
        // Initial (small, root-sized) bounds, as captured at construction time.
        var initial = BubbleBounds.fromTetreeKey(TetreeKey.create((byte) 10, 0L, 0L));
        var holder = new AtomicReference<>(initial);

        var manager = new GhostStateManager(holder::get, 1000);
        assertTrue(manager.toString().contains(initial.toString()),
                   "manager should initially report the supplier's current (initial) bounds");

        // Entities are added → tracker widens the bounds. Simulate by swapping in wider bounds.
        var widened = BubbleBounds.fromEntityPositions(
                List.of(new Point3f(0f, 0f, 0f), new Point3f(500f, 500f, 500f)), (byte) 10);
        holder.set(widened);

        // The manager must reflect the NEW bounds — proving it re-reads the supplier rather than
        // holding the construction-time snapshot.
        assertTrue(manager.toString().contains(widened.toString()),
                   "GhostStateManager must re-read live bounds from the supplier, not the stale snapshot");
        assertFalse(manager.toString().contains(initial.toString()),
                    "stale initial bounds must no longer be reported after the supplier changed");
    }

    @Test
    void valueConstructorStillSupportedForBackwardCompatibility() {
        var bounds = BubbleBounds.fromTetreeKey(TetreeKey.create((byte) 10, 0L, 0L));
        var manager = new GhostStateManager(bounds, 100);
        assertNotNull(manager.toString());
    }
}
