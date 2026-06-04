/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostBoundarySync;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for Luciferase-0frcy.96: GridGhostSyncAdapter ghost TTL was off-by-one. With
 * the strict-{@code <} predicate a ghost created at bucket B survived until currentBucket
 * reached B + GHOST_TTL_BUCKETS + 1, i.e. GHOST_TTL_BUCKETS + 1 ticks instead of the
 * documented GHOST_TTL_BUCKETS. The {@code <=} fix gives exactly GHOST_TTL_BUCKETS lifetime:
 * a ghost at bucket 0 is removed at bucket GHOST_TTL_BUCKETS, and survives bucket
 * GHOST_TTL_BUCKETS - 1.
 *
 * @author hal.hildebrand
 */
class GridGhostSyncAdapterTtlTest {

    private static final int TTL = GhostBoundarySync.GHOST_TTL_BUCKETS;

    @Test
    @SuppressWarnings("unchecked")
    void ghostLifetimeIsExactlyTtlBuckets() throws Exception {
        var config = GridConfiguration.DEFAULT_2X2;
        var grid = GridBubbleFactory.createBubbles(config, (byte) 10, 16L);
        var adapter = new GridGhostSyncAdapter(config, grid);

        var bubble = grid.getBubble(new BubbleCoordinate(0, 0));
        var bubbleId = bubble.id();

        // Seed one ghost created at bucket 0 directly into the adapter's storage.
        var ghostsField = GridGhostSyncAdapter.class.getDeclaredField("ghostsByBubble");
        ghostsField.setAccessible(true);
        var ghostsByBubble =
            (Map<UUID, Map<String, SimulationGhostEntity<StringEntityID, Object>>>) ghostsField.get(adapter);

        var position = new Point3f(1f, 1f, 1f);
        var id = new StringEntityID("ttl-96");
        var halo = new GhostEntityHalo<StringEntityID, Object>(
            id, new Object(), position, new EntityBounds(position, 0.5f), "tree-0");
        var ghost = new SimulationGhostEntity<>(halo, bubbleId, 0L /* bucket */, 0L, 0L);

        var inner = new ConcurrentHashMap<String, SimulationGhostEntity<StringEntityID, Object>>();
        inner.put(id.toDebugString(), ghost);
        ghostsByBubble.put(bubbleId, inner);

        assertEquals(1, adapter.getTotalGhostCount(), "Ghost seeded at bucket 0");

        // At bucket TTL-1 the ghost must still be alive (lifetime = TTL buckets).
        adapter.onBucketComplete(TTL - 1);
        assertEquals(1, adapter.getTotalGhostCount(),
                     "Ghost must survive through bucket TTL-1");

        // At bucket TTL the ghost must be expired (exactly TTL lifetime, not TTL+1).
        adapter.onBucketComplete(TTL);
        assertEquals(0, adapter.getTotalGhostCount(),
                     "Ghost must be removed at bucket TTL (off-by-one fixed)");
    }
}
