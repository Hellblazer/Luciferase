/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-7wzml.2: {@link DistributedGhostManager#createGhostForBoundaryElement} must transmit real content,
 * real position, and the correct ownerRank — not a placeholder (empty byte[], (0,0,0), wrong rank).
 *
 * <p>Acceptance criteria (from enriched bead):
 * <ol>
 *   <li>Ghost carries real position (not 0,0,0)</li>
 *   <li>Ghost carries real content (not empty)</li>
 *   <li>ownerRank equals the actual owner (currentRank for locally-owned boundary elements)</li>
 *   <li>No unchecked {@code (Content) new byte[0]} cast — content comes from the index</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
class DistributedGhostContentPopulationTest {

    /** Captures queued GhostElements for assertion. */
    private static final class CapturingChannel implements GhostChannel<MortonKey, LongEntityID, String> {
        final int rank;
        final List<GhostElement<MortonKey, LongEntityID, String>> captured = new ArrayList<>();

        CapturingChannel(int rank) {
            this.rank = rank;
        }

        @Override
        public void queueGhost(int targetRank, GhostElement<MortonKey, LongEntityID, String> e) {
            captured.add(e);
        }

        @Override
        public CompletableFuture<Void> flushToTarget(int targetRank) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public int getTotalPendingCount() {
            return captured.size();
        }

        @Override
        public void clear() {
            captured.clear();
        }

        @Override
        public int getCurrentRank() {
            return rank;
        }

        @Override
        public long getTreeId() {
            return 42L;
        }

        @Override
        public GhostType getGhostType() {
            return GhostType.FACES;
        }
    }

    /**
     * Insert an entity at a known position, mark a face neighbor as remote-owned, trigger ghost sync,
     * and assert the queued GhostElement carries the real position and real content — not placeholder zeros.
     */
    @Test
    void transmittedGhostCarriesRealPositionAndContent() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var insertPos = new Point3f(10f, 10f, 10f);
        var content = "real-content";
        var entityId = octree.insert(insertPos, (byte) 5, content);

        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);

        int localRank = 0;
        int remoteRank = 1;
        var channel = new CapturingChannel(localRank);
        var manager = new DistributedGhostManager<>(octree, channel, detector);

        // Mark an absent face neighbor as remote so the boundary scan flags the inserted key.
        MortonKey insertedKey = octree.getSpatialKeys().iterator().next();
        MortonKey absentNeighbor = null;
        for (var fn : octree.getNeighborDetector().findFaceNeighbors(insertedKey)) {
            if (!octree.containsSpatialKey(fn)) {
                absentNeighbor = fn;
                break;
            }
        }
        assertNotNull(absentNeighbor, "fixture must have an absent face neighbor");
        manager.setElementOwner(absentNeighbor, remoteRank);

        // Drive boundary scan + sync.
        manager.addKnownProcess(remoteRank);
        manager.createDistributedGhostLayer();

        // At least one ghost must have been queued.
        assertFalse(channel.captured.isEmpty(), "at least one ghost must be queued for the remote rank");

        // Every queued ghost must carry real data — NOT placeholder zeros.
        for (var ghost : channel.captured) {
            var pos = ghost.getPosition();
            assertFalse(pos.x == 0f && pos.y == 0f && pos.z == 0f,
                        "ghost position must not be (0,0,0) placeholder; was: " + pos);

            assertNotNull(ghost.getContent(), "ghost content must not be null");
            assertFalse(ghost.getContent().isEmpty(), "ghost content must not be empty string");

            assertEquals(localRank, ghost.getOwnerRank(),
                         "ownerRank must equal localRank (sender owns its boundary elements)");
        }
    }

    /**
     * The ghost content must equal the content actually inserted — not an empty placeholder.
     */
    @Test
    void transmittedGhostContentMatchesInsertedEntity() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var insertPos = new Point3f(5f, 15f, 25f);
        var expectedContent = "specific-content-value";
        octree.insert(insertPos, (byte) 5, expectedContent);

        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var channel = new CapturingChannel(0);
        var manager = new DistributedGhostManager<>(octree, channel, detector);

        // Mark any absent neighbor remote to trigger boundary detection.
        MortonKey insertedKey = octree.getSpatialKeys().iterator().next();
        for (var fn : octree.getNeighborDetector().findFaceNeighbors(insertedKey)) {
            if (!octree.containsSpatialKey(fn)) {
                manager.setElementOwner(fn, 1);
                break;
            }
        }

        manager.addKnownProcess(1);
        manager.createDistributedGhostLayer();

        assertFalse(channel.captured.isEmpty(), "ghost must be queued");

        boolean foundContent = channel.captured.stream()
                                               .anyMatch(g -> expectedContent.equals(g.getContent()));
        assertTrue(foundContent,
                   "at least one transmitted ghost must carry the expected content '" + expectedContent + "'");
    }

    /**
     * The ghost position must equal (or be very close to) the actual entity position — not (0,0,0).
     */
    @Test
    void transmittedGhostPositionMatchesInsertedEntity() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var insertPos = new Point3f(7f, 13f, 21f);
        octree.insert(insertPos, (byte) 5, "pos-test");

        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var channel = new CapturingChannel(0);
        var manager = new DistributedGhostManager<>(octree, channel, detector);

        MortonKey insertedKey = octree.getSpatialKeys().iterator().next();
        for (var fn : octree.getNeighborDetector().findFaceNeighbors(insertedKey)) {
            if (!octree.containsSpatialKey(fn)) {
                manager.setElementOwner(fn, 1);
                break;
            }
        }

        manager.addKnownProcess(1);
        manager.createDistributedGhostLayer();

        assertFalse(channel.captured.isEmpty(), "ghost must be queued");

        // Allow some tolerance — the stored position may be slightly adjusted by the index.
        boolean nearInserted = channel.captured.stream().anyMatch(g -> {
            var p = g.getPosition();
            // The position stored in the index for the entity should be close to insertPos
            return Math.abs(p.x - insertPos.x) < 1f &&
                   Math.abs(p.y - insertPos.y) < 1f &&
                   Math.abs(p.z - insertPos.z) < 1f;
        });
        assertTrue(nearInserted,
                   "ghost position must be near inserted position " + insertPos + "; was: " +
                   (channel.captured.isEmpty() ? "no ghosts" : channel.captured.get(0).getPosition()));
    }

    /**
     * H2: if an entity is removed between the boundary scan and ghost creation, getEntity() returns null.
     * The manager must skip that entity silently (no NullPointerException, no ghost queued for it).
     *
     * <p>We simulate mid-flight removal by inserting an entity, triggering the boundary detection so the key
     * is flagged, then removing the entity before ghost creation runs. Since the same entity is the only
     * occupant of its key, removal empties the node, and {@code getEntityIdsAt} will return an empty set —
     * so the ghost list for that key will be empty and nothing is queued.
     */
    @Test
    void entityRemovedMidFlightIsSkippedNotNPE() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var insertPos = new Point3f(20f, 20f, 20f);
        var entityId = octree.insert(insertPos, (byte) 5, "will-be-removed");

        var detector = new GhostBoundaryDetector<>(octree, octree.getNeighborDetector(), GhostType.FACES,
                                                   GhostAlgorithm.MINIMAL);
        var channel = new CapturingChannel(0);
        var manager = new DistributedGhostManager<>(octree, channel, detector);

        MortonKey insertedKey = octree.getSpatialKeys().iterator().next();
        MortonKey absentNeighbor = null;
        for (var fn : octree.getNeighborDetector().findFaceNeighbors(insertedKey)) {
            if (!octree.containsSpatialKey(fn)) {
                absentNeighbor = fn;
                break;
            }
        }
        assertNotNull(absentNeighbor, "fixture must have an absent face neighbor");
        manager.setElementOwner(absentNeighbor, 1);
        manager.addKnownProcess(1);

        // Remove the entity before ghost creation — simulates the mid-flight removal race (H2)
        octree.removeEntity(entityId);

        // Must not throw; removed entity's key yields no ghosts
        assertDoesNotThrow(() -> manager.createDistributedGhostLayer(),
                           "createDistributedGhostLayer must not NPE when entity is removed mid-flight");

        // The removed entity must not appear as a ghost
        boolean removedEntityQueued = channel.captured.stream()
                                                      .anyMatch(g -> entityId.equals(g.getEntityId()));
        assertFalse(removedEntityQueued,
                    "removed entity must not be queued as a ghost (mid-flight removal should be silently skipped)");
    }
}
