/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for natural topology evolution scenarios.
 * <p>
 * Tests complete lifecycle: detection → proposal → execution → validation
 *
 * @author hal.hildebrand
 */
class TopologyEvolutionTest {
    private static final TestClock JC1KH_CLOCK = new TestClock(1_000L); // determinism mandate (Luciferase-jc1kh)

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics metrics;
    private TopologyExecutor executor;
    private TopologyConsistencyValidator validator;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
        executor = new TopologyExecutor(bubbleGrid, accountant, metrics);
        validator = new TopologyConsistencyValidator();
    }

    @Test
    void testNaturalSplitEvolution() {
        // Start with single bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities until split threshold exceeded (>5000)
        addEntities(bubble, 5100);

        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        int totalEntitiesBefore = getTotalEntityCount();

        // Trigger split
        var centroid = bubble.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        // Execute split via the mechanism — public execute(SplitProposal) is AC-0-fenced
        var result = executor.executeInternal(proposal);

        // Verify evolution
        assertTrue(result.success(), "Split should succeed: " + result.message());
        assertEquals(5100, totalEntitiesBefore, "Should start with 5100 entities");
        assertEquals(5100, getTotalEntityCount(), "Entity count should be conserved");

        // FIX 5: Bey split always produces exactly 8 children (ALL kept, even empty ones) and
        // removes 1 parent → net +7 bubbles per successful split.
        int bubbleCountAfter = bubbleGrid.getAllBubbles().size();
        assertEquals(bubbleCountBefore + 7, bubbleCountAfter,
            "Bey split must produce exactly net +7 bubbles (8 children − 1 parent): was "
            + bubbleCountBefore + ", got " + bubbleCountAfter);

        // Verify entity conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testNaturalMergeEvolution() {
        // Start with 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add entities below merge threshold (<500 each)
        addEntities(bubble1, 300);
        addEntities(bubble2, 200);

        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        int totalEntitiesBefore = getTotalEntityCount();

        // Trigger merge
        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        // Execute merge
        var result = executor.execute(proposal);

        // RDR-018 AC-4: an arbitrary two-bubble merge is fenced (it would untile bubble2's region).
        // The "natural merge evolution" is no longer a supported single-step operation; it becomes
        // available once B-core (AC-2.5) supplies coverage-preserving sibling-collapse merges.
        assertFalse(result.success(), "Arbitrary two-bubble merge must be fenced (RDR-018 AC-4): " + result.message());
        assertEquals(500, totalEntitiesBefore, "Should start with 500 entities");
        assertEquals(500, getTotalEntityCount(), "Entity count must be conserved by a fenced merge");

        // No entity moved and no bubble removed — partition stays intact (no coverage hole)
        assertEquals(bubbleCountBefore, bubbleGrid.getAllBubbles().size(),
                     "Bubble count must be unchanged by a fenced merge");
        assertEquals(300, accountant.entitiesInBubble(bubble1.id()).size(), "bubble1 entities unchanged");
        assertEquals(200, accountant.entitiesInBubble(bubble2.id()).size(), "bubble2 entities unchanged");

        // Verify entity conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testNaturalMoveEvolution() {
        // Start with single bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities clustered away from center
        addEntities(bubble, 1000);

        int totalEntitiesBefore = getTotalEntityCount();

        // Get current centroid
        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        // Simulate entity clustering toward one corner
        var clusterCentroid = new Point3f(
            (float) currentCentroid.getX() + 1.0f,
            (float) currentCentroid.getY() + 1.0f,
            (float) currentCentroid.getZ() + 1.0f
        );

        var newCenter = new Point3f(
            (float) currentCentroid.getX() + 0.3f,
            (float) currentCentroid.getY() + 0.3f,
            (float) currentCentroid.getZ() + 0.3f
        );

        // Trigger move
        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        // Execute move
        var result = executor.execute(proposal);

        // Bubble relocation is deferred (Luciferase-0frcy.123): a no-op move must report
        // failure (and roll back), not silently succeed. Entity conservation must hold.
        assertFalse(result.success(), "Move must report failure while relocation is unimplemented");
        assertTrue(result.message().contains("not yet implemented"),
                   "Failure message must state the operation is not yet implemented: " + result.message());
        assertEquals(1000, totalEntitiesBefore, "Should start with 1000 entities");
        assertEquals(1000, getTotalEntityCount(), "Entity count should be conserved across the rolled-back move");

        // Verify no entities were moved (move only adjusts bubble boundaries)
        int entitiesAfter = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(1000, entitiesAfter, "All entities should remain in bubble");

        // Verify entity conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testSequentialEvolution() {
        // Start with 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Scenario: bubble1 grows (split), bubble2 stays small (merge candidate)
        addEntities(bubble1, 5100);  // Will split
        addEntities(bubble2, 200);   // Merge candidate

        int totalEntitiesBefore = getTotalEntityCount();
        assertEquals(5300, totalEntitiesBefore, "Should start with 5300 entities");

        // Step 1: Split bubble1
        var centroid = bubble1.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var splitProposal = new SplitProposal(
            UUID.randomUUID(),
            bubble1.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        var splitResult = executor.executeInternal(splitProposal); // public execute(SplitProposal) fenced — AC-0
        assertTrue(splitResult.success(), "Split should succeed");

        // FIX 5: Bey split always produces exactly 8 children (ALL kept) and removes 1 parent → net +7.
        int bubbleCountAfter = bubbleGrid.getAllBubbles().size();
        assertEquals(bubbleCountBefore + 7, bubbleCountAfter,
            "Bey split must produce exactly net +7 bubbles: was " + bubbleCountBefore + ", got " + bubbleCountAfter);

        // Verify entity conservation after split
        assertEquals(5300, getTotalEntityCount(), "Entity count should be conserved after split");

        var validation1 = accountant.validate();
        assertTrue(validation1.success(), "Entity validation should pass after split");

        // Step 2: Attempt merge of bubble2 with another small bubble
        // (This would require another small bubble to merge with - skip for now)

        // Final validation
        assertEquals(5300, getTotalEntityCount(), "Entity count should be conserved throughout");
    }

    @Test
    void testEntityConservationAcrossOperations() {
        // Create 3 bubbles — ensure the split-target bubble is at a level where its children
        // do not collide with existing bubbles.  Use createBubbles(1, level=1) for bubble1,
        // and separate bubbles at level 2 for bubble2/bubble3 so there is no key collision
        // when bubble1 (level 1) is split into level-2 children.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble1 = bubbleGrid.getAllBubbles().iterator().next();

        // Add bubble2 and bubble3 at a level-2 tet that is NOT a child of bubble1
        // (use a sibling of bubble1 so the children of bubble1 are free)
        var parentKey1 = bubbleGrid.getKeyForBubble(bubble1.id());
        var parentTet1 = com.hellblazer.luciferase.lucien.tetree.Tet.tetrahedron(parentKey1);
        // Pick a tet at level 2 that is a grandchild of a DIFFERENT level-0 subtree
        // by going child(4) twice from root type 0 — avoids bubble1's subtree
        var safeTet2 = new com.hellblazer.luciferase.lucien.tetree.Tet(0, 0, 0, (byte) 0, (byte) 0)
            .child(4).child(0);
        var safeTet3 = new com.hellblazer.luciferase.lucien.tetree.Tet(0, 0, 0, (byte) 0, (byte) 0)
            .child(4).child(1);
        var key2 = safeTet2.tmIndex();
        var key3 = safeTet3.tmIndex();

        // Only add bubble2/3 if their keys don't collide with bubble1's children
        var children1 = parentTet1.geometricSubdivide();
        var child1Keys = java.util.Arrays.stream(children1)
            .map(c -> c.tmIndex())
            .collect(java.util.stream.Collectors.toSet());
        boolean key2Safe = !bubbleGrid.containsBubble(key2) && !child1Keys.contains(key2);
        boolean key3Safe = !bubbleGrid.containsBubble(key3) && !child1Keys.contains(key3) && !key3.equals(key2);

        com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble2 = null;
        com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble3 = null;
        if (key2Safe) {
            bubble2 = new com.hellblazer.luciferase.simulation.bubble.EnhancedBubble(
                UUID.randomUUID(), (byte) 2, 10L);
            bubbleGrid.addBubble(bubble2, key2);
        }
        if (key3Safe) {
            bubble3 = new com.hellblazer.luciferase.simulation.bubble.EnhancedBubble(
                UUID.randomUUID(), (byte) 2, 10L);
            bubbleGrid.addBubble(bubble3, key3);
        }

        addEntities(bubble1, 5100);  // Will split
        if (bubble2 != null) addEntities(bubble2, 300);
        if (bubble3 != null) addEntities(bubble3, 200);

        int initialTotal = getTotalEntityCount();
        int initialBubbles = bubbleGrid.getAllBubbles().size();

        // Execute split on bubble1
        var centroid = bubble1.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var splitProposal = new SplitProposal(
            UUID.randomUUID(),
            bubble1.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        var splitResult = executor.executeInternal(splitProposal); // public execute(SplitProposal) fenced — AC-0
        assertTrue(splitResult.success(), "Split must succeed for +7 test: " + splitResult.message());

        // FIX 5: Bey split always produces exactly 8 children (ALL kept) and removes 1 parent → net +7.
        int bubbleCountAfterSplit = bubbleGrid.getAllBubbles().size();
        assertEquals(bubbleCountBefore + 7, bubbleCountAfterSplit,
            "Bey split must produce exactly net +7 bubbles: was " + bubbleCountBefore + ", got " + bubbleCountAfterSplit);

        // Verify conservation after split
        assertEquals(initialTotal, getTotalEntityCount(), "Entity count should be conserved after split");

        // Execute merge of two child bubbles (they are arbitrary two-bubble merges → fenced)
        // Pick two children from the result to attempt a merge
        var childBubbles = bubbleGrid.getAllBubbles().stream()
            .filter(b -> !b.id().equals(bubble1.id()))
            .limit(2)
            .toList();
        if (childBubbles.size() >= 2) {
            var mergeProposal = new MergeProposal(
                UUID.randomUUID(),
                childBubbles.get(0).id(),
                childBubbles.get(1).id(),
                DigestAlgorithm.DEFAULT.getOrigin(),
                JC1KH_CLOCK.currentTimeMillis()
            );

            var mergeResult = executor.execute(mergeProposal);

            // RDR-018 AC-4: the merge is fenced. Conservation across operations — the property this
            // test pins — still holds: the fenced merge mutates nothing, so the count is unchanged.
            assertFalse(mergeResult.success(), "Arbitrary two-bubble merge must be fenced (RDR-018 AC-4)");
            int bubbleCountAfterMerge = bubbleGrid.getAllBubbles().size();
            assertEquals(bubbleCountAfterSplit, bubbleCountAfterMerge,
                "Bubble count must be unchanged by a fenced merge (no region untiled)");

            // Verify conservation after the fenced merge
            assertEquals(initialTotal, getTotalEntityCount(), "Entity count should be conserved after merge");
        }

        // Final validation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass after all operations");
    }

    // Helper methods

    private void addEntities(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }
    }

    private int getTotalEntityCount() {
        return accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
    }
}
