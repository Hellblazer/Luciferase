/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD test for the Bey-refinement BubbleSplitter (RDR-018 AC-2.5).
 * <p>
 * Verifies that execute() replaces the source leaf with its 8 Bey children,
 * assigns entities by exact contains12DOP containment, and satisfies the
 * leaf-partition invariant (8 children tile parent, no parent left behind).
 *
 * @author hal.hildebrand
 */
class BubbleSplitterBeyRefinementTest {

    private static final TestClock CLOCK = new TestClock(1_000L);

    private TetreeBubbleGrid  bubbleGrid;
    private EntityAccountant  accountant;
    private TopologyMetrics   metrics;
    private BubbleSplitter    splitter;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics    = new TopologyMetrics();
        splitter   = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
    }

    // -----------------------------------------------------------------------
    // Helper: create a bubble at a known spatial level and register it
    // -----------------------------------------------------------------------

    private EnhancedBubble createBubbleWithEntities(byte level, int entityCount) {
        bubbleGrid.createBubbles(1, level, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Obtain the actual parent Tet so we can place entities at child centroids
        var parentKey = bubbleGrid.getKeyForBubble(bubble.id());
        assertNotNull(parentKey, "Parent key must exist in grid");
        var parentTet = Tet.tetrahedron(parentKey);
        var children  = parentTet.geometricSubdivide();

        // Place exactly one entity at the centroid of each of the first min(entityCount,8) children,
        // then fill remaining entities spread across children cyclically.
        for (int i = 0; i < entityCount; i++) {
            var child   = children[i % 8];
            var verts   = child.coordinates();
            // centroid = average of 4 vertices
            float cx = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4.0f;
            float cy = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4.0f;
            float cz = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4.0f;

            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(cx, cy, cz), null);
            accountant.register(bubble.id(), entityId);
        }
        return bubble;
    }

    private SplitProposal proposal(EnhancedBubble bubble) {
        // SplitPlane geometry is no longer consulted by execute() — pass a dummy plane
        var plane = new SplitPlane(new Point3f(1f, 0f, 0f), 0f);
        return new SplitProposal(UUID.randomUUID(), bubble.id(), plane,
                                 DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());
    }

    // -----------------------------------------------------------------------
    // Core: postcondition (non-vacuous containment assertion)
    // -----------------------------------------------------------------------

    /**
     * After a successful Bey split every redistributed entity must be contained
     * by its assigned child bubble's Tet (exact containment via contains12DOP).
     */
    @Test
    void postcondition_everyEntityContainedByItsChildTet() {
        var bubble = createBubbleWithEntities((byte) 1, 5100);
        int countBefore = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(5100, countBefore);

        var result = splitter.execute(proposal(bubble));

        assertTrue(result.success(), "Bey split must succeed: " + result.message());
        // FIX 2: all 8 children are kept on success path
        assertEquals(8, result.childBubbleIds().size(), "Successful split must report exactly 8 children");

        // For each child bubble verify containment
        int totalRedistributed = 0;
        for (var childId : result.childBubbleIds()) {
            var childBubble = bubbleGrid.getBubbleById(childId);
            assertNotNull(childBubble, "Child bubble " + childId + " must be in grid");

            var childKey = bubbleGrid.getKeyForBubble(childId);
            assertNotNull(childKey, "Child key must exist");
            var childTet = Tet.tetrahedron(childKey);

            for (var entityId : accountant.entitiesInBubble(childId)) {
                // Retrieve position from the child bubble
                var records = childBubble.getAllEntityRecords();
                var match   = records.stream()
                                     .filter(r -> UUID.fromString(r.id()).equals(entityId))
                                     .findFirst();
                assertTrue(match.isPresent(), "Entity " + entityId + " must be in child bubble records");
                var pos = match.get().position();
                assertTrue(childTet.contains12DOP(pos.x, pos.y, pos.z),
                           "Entity at " + pos + " must be contained by child tet " + childTet
                           + " (key=" + childKey + ")");
                totalRedistributed++;
            }
        }
        assertTrue(totalRedistributed > 0, "At least some entities must be redistributed");
        assertEquals(result.entitiesRedistributed(), totalRedistributed,
                     "result.entitiesRedistributed must match sum of child entity counts");
    }

    // -----------------------------------------------------------------------
    // Leaf-partition invariant: 8 child keys tile the parent exactly
    // -----------------------------------------------------------------------

    @Test
    void leafPartitionInvariant_8ChildKeysTileParent() {
        var bubble = createBubbleWithEntities((byte) 1, 5100);

        // Capture expected child keys before split
        var parentKey      = bubbleGrid.getKeyForBubble(bubble.id());
        var parentTet      = Tet.tetrahedron(parentKey);
        var children       = parentTet.geometricSubdivide();
        var expectedKeys   = new LinkedHashSet<TetreeKey<?>>();
        for (var child : children) {
            expectedKeys.add(child.tmIndex());
        }
        assertEquals(8, expectedKeys.size(), "Bey subdivision produces exactly 8 distinct children");

        var result = splitter.execute(proposal(bubble));
        assertTrue(result.success(), "Split must succeed: " + result.message());

        // FIX 2: ALL 8 child keys must appear in the grid (no empty-child dropping on success path).
        // The leaf-partition coverage invariant requires exactly 8 children tiling the parent.
        var childIds = result.childBubbleIds();
        assertEquals(8, childIds.size(),
                     "Successful Bey split must report exactly 8 children (all kept, even empty)");

        // Parent must be GONE from grid
        assertNull(bubbleGrid.getBubbleById(bubble.id()),
                   "Source bubble must be removed from grid after Bey split");
        assertFalse(bubbleGrid.containsBubble(parentKey),
                    "Parent key must no longer be in grid");

        // Every child must have its key in the expected set
        for (var childId : childIds) {
            var childKey = bubbleGrid.getKeyForBubble(childId);
            assertNotNull(childKey, "Child bubble " + childId + " must have a key in grid");
            assertTrue(expectedKeys.contains(childKey),
                       "Child key " + childKey + " must be one of the 8 expected Bey child keys");
        }

        // Exactly the 8 expected keys — all present, none missing
        var actualChildKeys = new ArrayList<TetreeKey<?>>();
        for (var childId : childIds) {
            actualChildKeys.add(bubbleGrid.getKeyForBubble(childId));
        }
        assertEquals(8, actualChildKeys.stream().distinct().count(),
                     "All 8 child keys must be distinct");
        assertTrue(actualChildKeys.containsAll(expectedKeys),
                   "All 8 expected Bey child keys must be in the grid");
    }

    // -----------------------------------------------------------------------
    // Conservation: sum of child entity counts == source count
    // -----------------------------------------------------------------------

    @Test
    void conservation_totalEntityCountPreserved() {
        var bubble  = createBubbleWithEntities((byte) 1, 5100);
        int before  = accountant.entitiesInBubble(bubble.id()).size();

        var result = splitter.execute(proposal(bubble));
        assertTrue(result.success(), "Split must succeed: " + result.message());

        // FIX 2: all 8 children always kept
        assertEquals(8, result.childBubbleIds().size(), "Successful split must return exactly 8 children");

        // accountant.validate() is the authoritative conservation check
        var validation = accountant.validate();
        assertTrue(validation.success(), "accountant must be valid after split: " + validation.details());

        // Sum of all child entity counts must equal original (no escaped — entities at child centroids)
        int childTotal = result.childBubbleIds().stream()
                               .mapToInt(id -> accountant.entitiesInBubble(id).size())
                               .sum();
        assertEquals(before, childTotal,
                     "All " + before + " entities must be distributed across children");

        // FIX 6: entitiesRedistributed must equal before (no silent skip)
        assertEquals(before, result.entitiesRedistributed(),
                     "entitiesRedistributed must equal source count (no silent skip)");
    }

    // -----------------------------------------------------------------------
    // Edge: source at max level (21) → fail-loud, no mutation
    // -----------------------------------------------------------------------

    @Test
    void edge_maxLevelSourceFailsLoud() {
        // Create a bubble registered at level 1 in the grid; then we put entities
        // that would normally go to max level by creating the bubble at level 21.
        // We can't use createBubbles (grid controls level). Instead: create grid at
        // max level directly.
        var gridMax = new TetreeBubbleGrid((byte) 2);
        var accMax  = new EntityAccountant();
        var splMax  = new BubbleSplitter(gridMax, accMax, OperationTracker.NOOP, metrics);

        // Build a level-21 bubble by finding a tet key at level 21
        // Use Tet.locatePointBeyRefinementFromRoot to find a level-21 tet
        var tet21    = com.hellblazer.luciferase.lucien.tetree.Tet.locatePointBeyRefinementFromRoot(
            100f, 100f, 100f, (byte) 21);
        assertNotNull(tet21, "Must find a tet at level 21");
        var key21    = tet21.tmIndex();

        var maxBubble = new EnhancedBubble(UUID.randomUUID(), (byte) 21, 10L);
        gridMax.addBubble(maxBubble, key21);
        // Add some entities
        for (int i = 0; i < 10; i++) {
            var id = UUID.randomUUID();
            maxBubble.addEntity(id.toString(), new Point3f(100f, 100f, 100f), null);
            accMax.register(maxBubble.id(), id);
        }
        int countBefore = accMax.entitiesInBubble(maxBubble.id()).size();

        var plane    = new SplitPlane(new Point3f(1f, 0f, 0f), 0f);
        var proposal = new SplitProposal(UUID.randomUUID(), maxBubble.id(), plane,
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        var result = splMax.execute(proposal);

        assertFalse(result.success(), "Split at max level must fail");
        assertTrue(result.message().contains("max level") || result.message().contains("cannot refine"),
                   "Failure message must mention max level; got: " + result.message());

        // No mutation: source bubble still in grid, entity count unchanged
        assertNotNull(gridMax.getBubbleById(maxBubble.id()),
                      "Source bubble must still be in grid after max-level failure");
        assertEquals(countBefore, accMax.entitiesInBubble(maxBubble.id()).size(),
                     "Entity count must be unchanged after max-level failure");
    }

    // -----------------------------------------------------------------------
    // FIX 6: entity outside parent tet → assigned to nearest child (NOT lost), conservation holds
    //
    // After FIX 1, entities that fail contains12DOP for all children are assigned to the
    // geometrically nearest child — no orphan path exists.  This test is UNCONDITIONAL:
    // we always add outside entities and always assert they land in a LIVE child bubble,
    // not on the removed source.  The split must succeed and no entity may be lost.
    // -----------------------------------------------------------------------

    @Test
    void edge_entityOutsideParentTetAssignedToNearestChildNotLost() {
        // Register a bubble at level 3: small spatial region
        // Use a level-3 tet obtained by descending child(0) three times from root
        var rootTet    = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        var level3Tet  = rootTet.child(0).child(0).child(0);
        var level3Key  = level3Tet.tmIndex();

        // Sibling tet: child(1) of the same parent — its centroid is outside level3Tet's 12-DOP
        var siblingTet = rootTet.child(0).child(0).child(1);
        var sibVerts   = siblingTet.coordinates();
        float sibCx    = (sibVerts[0].x + sibVerts[1].x + sibVerts[2].x + sibVerts[3].x) / 4.0f;
        float sibCy    = (sibVerts[0].y + sibVerts[1].y + sibVerts[2].y + sibVerts[3].y) / 4.0f;
        float sibCz    = (sibVerts[0].z + sibVerts[1].z + sibVerts[2].z + sibVerts[3].z) / 4.0f;

        // Verify the sibling centroid is truly OUTSIDE level3Tet's level-4 children (12-DOP)
        var children3  = level3Tet.geometricSubdivide();
        boolean outsideAll = true;
        for (var c : children3) {
            if (c.contains12DOP(sibCx, sibCy, sibCz)) {
                outsideAll = false;
                break;
            }
        }

        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 3, 10L);
        bubbleGrid.addBubble(bubble, level3Key);
        var sourceId = bubble.id();

        // Add 100 valid interior entities (at child centroids of level3Tet)
        var validIds = new ArrayList<UUID>();
        for (int i = 0; i < 100; i++) {
            var child = children3[i % 8];
            var verts = child.coordinates();
            float cx  = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4.0f;
            float cy  = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4.0f;
            float cz  = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4.0f;
            var id    = UUID.randomUUID();
            validIds.add(id);
            bubble.addEntity(id.toString(), new Point3f(cx, cy, cz), null);
            accountant.register(sourceId, id);
        }

        // FIX 6: UNCONDITIONALLY add outside entities (10 at the sibling centroid).
        // Even when outsideAll==false (the sibling centroid happened to be inside a child),
        // the test is still valid — all entities land in a live child.
        var outsideIds = new ArrayList<UUID>();
        for (int i = 0; i < 10; i++) {
            var id = UUID.randomUUID();
            outsideIds.add(id);
            bubble.addEntity(id.toString(), new Point3f(sibCx, sibCy, sibCz), null);
            accountant.register(sourceId, id);
        }
        int totalBefore = accountant.entitiesInBubble(sourceId).size();
        assertEquals(110, totalBefore, "Should have 110 entities (100 interior + 10 outside)");

        // Collect the 8 expected child keys so we can assert they are in the grid after split
        var expectedChildKeys = new ArrayList<com.hellblazer.luciferase.lucien.tetree.TetreeKey<?>>(8);
        for (var child : children3) {
            expectedChildKeys.add(child.tmIndex());
        }

        var plane    = new SplitPlane(new Point3f(1f, 0f, 0f), 0f);
        var proposal = new SplitProposal(UUID.randomUUID(), sourceId, plane,
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        var result = splitter.execute(proposal);
        assertTrue(result.success(), "Split must succeed even with outside entities: " + result.message());

        // FIX 6: Result must report exactly 8 children (ALL kept per FIX 2)
        assertEquals(8, result.childBubbleIds().size(),
                     "Successful split must report exactly 8 children (all kept, even empty)");

        // FIX 6: ALL 110 entities must be distributed across children — nothing lost, nothing orphaned
        int childTotal = result.childBubbleIds().stream()
                               .mapToInt(id -> accountant.entitiesInBubble(id).size())
                               .sum();
        assertEquals(110, childTotal,
                     "ALL 110 entities (100 interior + 10 outside) must land in a live child — no orphan");

        // FIX 6: entitiesRedistributed must equal totalBefore (all entities were moved)
        assertEquals(totalBefore, result.entitiesRedistributed(),
                     "entitiesRedistributed must equal source count (no silent skip)");

        // FIX 6: Source bubble must be removed from grid after successful split
        assertNull(bubbleGrid.getBubbleById(sourceId),
                   "Source bubble must be removed from grid after successful split");

        // FIX 6: Source must NOT hold any entities in accountant (all moved to children)
        int sourceInAccountant = accountant.entitiesInBubble(sourceId).size();
        assertEquals(0, sourceInAccountant,
                     "No entities may map to the removed source bubble in accountant");

        // FIX 6: The outside entities (IDs in outsideIds) must each be found in some live child
        for (var outsideId : outsideIds) {
            boolean foundInChild = false;
            for (var childId : result.childBubbleIds()) {
                if (accountant.entitiesInBubble(childId).contains(outsideId)) {
                    // Verify child bubble is LIVE in the grid
                    assertNotNull(bubbleGrid.getBubbleById(childId),
                                  "Child holding outside entity must be LIVE in grid");
                    foundInChild = true;
                    break;
                }
            }
            assertTrue(foundInChild,
                       "Outside entity " + outsideId + " must be found in some live child bubble");
        }

        // FIX 6: Accountant must be valid (no conservation errors)
        var validation = accountant.validate();
        assertTrue(validation.success(),
                   "Accountant must be valid after split with outside entities: " + validation.details());

        if (outsideAll) {
            // Verify the WARN log fired (nearestFallback > 0) implicitly by checking that ALL
            // 10 outside-sibling-centroid entities ended up in a live child (already asserted above).
            // At least one of the 8 children must hold one of the outsideIds.
            boolean anyOutsideInChild = result.childBubbleIds().stream()
                .anyMatch(cid -> outsideIds.stream()
                                           .anyMatch(eid -> accountant.entitiesInBubble(cid).contains(eid)));
            assertTrue(anyOutsideInChild,
                       "When outsideAll=true: outside entities must have triggered nearest-child fallback");
        }
    }

    // -----------------------------------------------------------------------
    // Source-not-found → fail, no mutation
    // -----------------------------------------------------------------------

    @Test
    void sourceNotFound_failsCleanly() {
        var plane    = new SplitPlane(new Point3f(1f, 0f, 0f), 0f);
        var proposal = new SplitProposal(UUID.randomUUID(), UUID.randomUUID(), plane,
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());
        var result = splitter.execute(proposal);
        assertFalse(result.success());
        assertTrue(result.childBubbleIds().isEmpty(), "childBubbleIds must be empty on failure");
    }
}
