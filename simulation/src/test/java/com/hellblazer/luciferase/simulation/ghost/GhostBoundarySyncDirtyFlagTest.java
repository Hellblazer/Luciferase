/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.bubble.ExternalBubbleTracker;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.101: {@code GhostBoundarySync.onBucketComplete()} previously
 * re-transmitted every tracked ghost on every bucket boundary, regardless of change. The fix only sends
 * ghosts that changed since their last send (dirty), plus a heartbeat for unchanged ghosts approaching
 * TTL expiry. This test pins: (1) an unchanged ghost is NOT re-sent on the immediately-following bucket;
 * (2) a re-added (updated) ghost IS re-sent; (3) an unchanged ghost IS re-sent as a heartbeat before TTL.
 *
 * @author hal.hildebrand
 */
class GhostBoundarySyncDirtyFlagTest {

    static final class TestEntityID implements EntityID {
        private final String id;
        TestEntityID(String id) { this.id = id; }
        @Override public String toDebugString() { return id; }
        @Override public int compareTo(EntityID other) { return id.compareTo(other.toDebugString()); }
        @Override public boolean equals(Object o) { return o instanceof TestEntityID t && id.equals(t.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private GhostBoundarySync<TestEntityID, String> sync;
    private AtomicInteger sendCount;
    private List<SimulationGhostEntity<TestEntityID, String>> sent;
    private UUID neighborId;
    private UUID sourceBubbleId;

    @BeforeEach
    void setUp() {
        sendCount = new AtomicInteger(0);
        sent = new ArrayList<>();
        sync = new GhostBoundarySync<>(new ExternalBubbleTracker(), new GhostLayerHealth(),
                                       (n, ghosts) -> { sendCount.addAndGet(ghosts.size()); sent.addAll(ghosts); });
        neighborId = UUID.randomUUID();
        sourceBubbleId = UUID.randomUUID();
    }

    private void addGhost(String entityId, long bucket) {
        var p = new Point3f(0.5f, 0.5f, 0.5f);
        var halo = new GhostEntityHalo<>(new TestEntityID(entityId), "c", p, new EntityBounds(p, 0.1f), "tree-A");
        sync.addGhost(halo, sourceBubbleId, neighborId, bucket);
    }

    @Test
    void unchangedGhostIsNotResentNextBucket() {
        addGhost("e1", 0L);

        sync.onBucketComplete(0L);
        assertEquals(1, sendCount.get(), "ghost must be sent on first bucket completion");

        // Next bucket, no change to the ghost: it must NOT be re-broadcast.
        sync.onBucketComplete(1L);
        assertEquals(1, sendCount.get(),
                     "unchanged ghost must not be re-sent on the next bucket (Luciferase-0frcy.101)");
    }

    @Test
    void updatedGhostIsResent() {
        addGhost("e1", 0L);
        sync.onBucketComplete(0L);
        assertEquals(1, sendCount.get());

        // Re-add (update) the same entity at a later bucket: now dirty, must be re-sent.
        addGhost("e1", 1L);
        sync.onBucketComplete(1L);
        assertEquals(2, sendCount.get(), "updated ghost must be re-sent (Luciferase-0frcy.101)");
    }

    @Test
    void unchangedGhostHeartbeatsBeforeExpiry() {
        addGhost("e1", 0L);
        sync.onBucketComplete(0L);
        assertEquals(1, sendCount.get());

        // Quiet buckets 1..3: no re-send for an unchanged ghost.
        sync.onBucketComplete(1L);
        sync.onBucketComplete(2L);
        sync.onBucketComplete(3L);
        assertEquals(1, sendCount.get(), "no redundant re-sends while well within TTL");

        // TTL is 5 buckets; at bucket 4 (TTL-1 since last send) the unchanged ghost is heartbeated so
        // the neighbor's copy is refreshed before it would be culled.
        sync.onBucketComplete(4L);
        assertEquals(2, sendCount.get(),
                     "unchanged ghost must heartbeat before TTL expiry (Luciferase-0frcy.101)");
    }
}
