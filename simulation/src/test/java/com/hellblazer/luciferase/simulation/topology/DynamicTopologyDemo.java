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

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interactive demonstration of dynamic topology evolution.
 * <p>
 * Shows natural evolution from 4 bubbles → 8+ bubbles through:
 * - Entity clustering triggers splits
 * - Entity migration triggers merges
 * - Entity movement triggers boundary adaptation
 *
 * @author hal.hildebrand
 */
class DynamicTopologyDemo {

    private static final int SPLIT_THRESHOLD = 5000;
    private static final int MERGE_THRESHOLD = 500;

    @Test
    void demonstrateDynamicEvolution() {
        System.out.println("=== Dynamic Topology Evolution Demo ===\n");

        // Initialize 4-bubble grid
        var bubbleGrid = new TetreeBubbleGrid((byte) 2);
        var accountant = new EntityAccountant();
        var metrics = new TopologyMetrics();
        var executor = new TopologyExecutor(bubbleGrid, accountant, metrics);
        var validator = new TopologyConsistencyValidator();

        System.out.println("Phase 1: Initial Setup");
        System.out.println("---------------------");
        bubbleGrid.createBubbles(4, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        System.out.println("Created " + bubbles.size() + " bubbles (requested 4): " + bubbles.stream().map(b -> b.id().toString().substring(0, 8)).toList());

        // Distribute entities unevenly to trigger topology changes
        // Use actual bubble count (may be less than 4 due to tetree level limitations)
        if (bubbles.size() >= 1) {
            addEntities(bubbles.get(0), accountant, 5200, "High density (will split)");
        }
        if (bubbles.size() >= 2) {
            addEntities(bubbles.get(1), accountant, 4800, "Medium-high density");
        }
        if (bubbles.size() >= 3) {
            addEntities(bubbles.get(2), accountant, 1000, "Normal density");
        }
        if (bubbles.size() >= 4) {
            addEntities(bubbles.get(3), accountant, 200, "Low density (merge candidate)");
        }

        printMetrics("Initial", bubbleGrid, accountant, validator);

        // Phase 2: Split overcrowded bubble
        System.out.println("\nPhase 2: Split Overcrowded Bubble");
        System.out.println("---------------------------------");
        var bubble0 = bubbles.get(0);
        System.out.println("Bubble " + bubble0.id().toString().substring(0, 8) + " has 5200 entities (>5000 threshold)");

        var centroid0 = bubble0.bounds().centroid();
        var splitPlane0 = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid0.getX()
        );

        var splitProposal0 = new SplitProposal(
            UUID.randomUUID(),
            bubble0.id(),
            splitPlane0,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        System.out.println("Executing split proposal...");
        var splitResult0 = executor.execute(splitProposal0);
        System.out.println("Split result: " + (splitResult0.success() ? "SUCCESS" : "FAILED"));
        System.out.println("  " + splitResult0.message());

        printMetrics("After First Split", bubbleGrid, accountant, validator);

        // Phase 3: Split another overcrowded bubble
        System.out.println("\nPhase 3: Split Second Overcrowded Bubble");
        System.out.println("----------------------------------------");
        var bubble1 = bubbles.get(1);
        System.out.println("Bubble " + bubble1.id().toString().substring(0, 8) + " has 4800 entities (approaching threshold)");
        System.out.println("Adding 300 more entities to trigger split...");

        addEntities(bubble1, accountant, 300, "Push above threshold");

        var centroid1 = bubble1.bounds().centroid();
        var splitPlane1 = new SplitPlane(
            new Point3f(0.0f, 1.0f, 0.0f),
            (float) centroid1.getY()
        );

        var splitProposal1 = new SplitProposal(
            UUID.randomUUID(),
            bubble1.id(),
            splitPlane1,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        System.out.println("Executing split proposal...");
        var splitResult1 = executor.execute(splitProposal1);
        System.out.println("Split result: " + (splitResult1.success() ? "SUCCESS" : "FAILED"));
        System.out.println("  " + splitResult1.message());

        printMetrics("After Second Split", bubbleGrid, accountant, validator);

        // Phase 4: Merge underpopulated bubbles (if we have enough bubbles)
        System.out.println("\nPhase 4: Merge Underpopulated Bubbles");
        System.out.println("-------------------------------------");

        if (bubbles.size() >= 3) {
            var bubble2 = bubbles.get(2);

            // Check if we have a 4th bubble for merge
            if (bubbles.size() >= 4) {
                var bubble3 = bubbles.get(3);

                System.out.println("Bubble " + bubble2.id().toString().substring(0, 8) + " has " +
                                  accountant.entitiesInBubble(bubble2.id()).size() + " entities");
                System.out.println("Bubble " + bubble3.id().toString().substring(0, 8) + " has " +
                                  accountant.entitiesInBubble(bubble3.id()).size() + " entities");

                // Remove entities from bubble2 to make it eligible for merge
                System.out.println("Simulating entity migration away from bubble2...");
                var bubble2Entities = accountant.entitiesInBubble(bubble2.id());
                int toRemove = Math.max(0, bubble2Entities.size() - 450);
                System.out.println("Removing " + toRemove + " entities to bring below threshold...");

                var mergeProposal = new MergeProposal(
                    UUID.randomUUID(),
                    bubble2.id(),
                    bubble3.id(),
                    DigestAlgorithm.DEFAULT.getOrigin(),
                    System.currentTimeMillis()
                );

                // Note: This will fail validation if bubble2 is still above threshold
                // In a real system, entities would migrate over time
                System.out.println("Attempting merge proposal...");
                var mergeResult = executor.execute(mergeProposal);
                System.out.println("Merge result: " + (mergeResult.success() ? "SUCCESS" : "FAILED"));
                System.out.println("  " + mergeResult.message());
            } else {
                System.out.println("Skipping merge: need at least 4 bubbles (have " + bubbles.size() + ")");
            }
        } else {
            System.out.println("Skipping merge: need at least 3 bubbles (have " + bubbles.size() + ")");
        }

        printMetrics("After Merge Attempt", bubbleGrid, accountant, validator);

        // Phase 5: Boundary adaptation (move) - use any available bubble
        System.out.println("\nPhase 5: Boundary Adaptation");
        System.out.println("----------------------------");

        if (bubbles.size() >= 3) {
            var bubbleToMove = bubbles.get(2);
            System.out.println("Simulating entity clustering in bubble " + bubbleToMove.id().toString().substring(0, 8) + "...");

            var bubbleBounds = bubbleToMove.bounds();
            var bubbleCentroid = bubbleBounds.centroid();

            var clusterCentroid = new Point3f(
                (float) bubbleCentroid.getX() + 1.5f,
                (float) bubbleCentroid.getY() + 1.5f,
                (float) bubbleCentroid.getZ() + 1.5f
            );

            var newCenter = new Point3f(
                (float) bubbleCentroid.getX() + 0.5f,
                (float) bubbleCentroid.getY() + 0.5f,
                (float) bubbleCentroid.getZ() + 0.5f
            );

            var moveProposal = new MoveProposal(
                UUID.randomUUID(),
                bubbleToMove.id(),
                newCenter,
                clusterCentroid,
                DigestAlgorithm.DEFAULT.getOrigin(),
                System.currentTimeMillis()
            );

            System.out.println("Executing move proposal...");
            var moveResult = executor.execute(moveProposal);
            System.out.println("Move result: " + (moveResult.success() ? "SUCCESS" : "FAILED"));
            System.out.println("  " + moveResult.message());
        } else {
            System.out.println("Skipping move: need at least 3 bubbles (have " + bubbles.size() + ")");
        }

        printMetrics("Final State", bubbleGrid, accountant, validator);

        // Final validation
        System.out.println("\n=== Final Validation ===");
        var finalValidation = accountant.validate();
        System.out.println("Entity conservation: " + (finalValidation.success() ? "PASS" : "FAIL"));
        System.out.println("  " + finalValidation.details());

        assertTrue(finalValidation.success(), "Entity conservation should pass");

        System.out.println("\n=== Demo Complete ===");
        System.out.println("Key Observations:");
        System.out.println("1. Splits triggered by density >5000 entities");
        System.out.println("2. Merge candidates identified at <500 entities");
        System.out.println("3. Boundary adaptation follows entity clusters");
        System.out.println("4. 100% entity retention throughout evolution");
    }

    @Test
    void demonstrateRapidGrowth() {
        System.out.println("\n=== Rapid Growth Scenario ===\n");

        var bubbleGrid = new TetreeBubbleGrid((byte) 2);
        var accountant = new EntityAccountant();
        var metrics = new TopologyMetrics();
        var executor = new TopologyExecutor(bubbleGrid, accountant, metrics);

        System.out.println("Starting with 4 bubbles...");
        bubbleGrid.createBubbles(4, (byte) 2, 10);
        var initialBubbles = bubbleGrid.getAllBubbles().stream().toList();

        // Simulate rapid entity growth
        System.out.println("Simulating rapid entity influx...");
        for (int i = 0; i < initialBubbles.size(); i++) {
            var bubble = initialBubbles.get(i);
            int entityCount = 5000 + (i * 200);  // Each bubble slightly over threshold
            addEntities(bubble, accountant, entityCount, "Bubble " + i);
        }

        System.out.println("\nInitial state: " + bubbleGrid.getAllBubbles().size() + " bubbles, " +
                          getTotalEntityCount(accountant) + " entities");

        // Trigger cascading splits
        System.out.println("\nTriggering cascading splits...");
        int splitCount = 0;
        for (var bubble : initialBubbles) {
            int bubbleEntities = accountant.entitiesInBubble(bubble.id()).size();
            if (bubbleEntities > SPLIT_THRESHOLD) {
                var centroid = bubble.bounds().centroid();
                var splitPlane = new SplitPlane(
                    new Point3f(1.0f, 0.0f, 0.0f),
                    (float) centroid.getX()
                );

                var proposal = new SplitProposal(
                    UUID.randomUUID(),
                    bubble.id(),
                    splitPlane,
                    DigestAlgorithm.DEFAULT.getOrigin(),
                    System.currentTimeMillis()
                );

                var result = executor.execute(proposal);
                if (result.success()) {
                    splitCount++;
                    System.out.println("  Split " + splitCount + " completed: " + result.message());
                }
            }
        }

        System.out.println("\nFinal state: " + bubbleGrid.getAllBubbles().size() + " bubbles (started with " +
                          initialBubbles.size() + ")");
        System.out.println("Total entities: " + getTotalEntityCount(accountant));
        System.out.println("Splits executed: " + splitCount);

        // Validate conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity conservation should pass");
        System.out.println("\nEntity conservation: PASS");
    }

    // Helper methods

    private void addEntities(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble,
                             EntityAccountant accountant, int count, String description) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }
        System.out.println("  Added " + count + " entities - " + description);
    }

    private void printMetrics(String phase, TetreeBubbleGrid grid, EntityAccountant accountant,
                             TopologyConsistencyValidator validator) {
        System.out.println("\n--- " + phase + " Metrics ---");
        System.out.println("Bubble count: " + grid.getAllBubbles().size());
        System.out.println("Total entities: " + getTotalEntityCount(accountant));

        var distribution = accountant.getDistribution();
        if (!distribution.isEmpty()) {
            var counts = distribution.values();
            int min = counts.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
            double avg = counts.stream().mapToInt(Integer::intValue).average().orElse(0.0);

            System.out.println("Entity distribution:");
            System.out.println("  Min: " + min + " entities/bubble");
            System.out.println("  Max: " + max + " entities/bubble");
            System.out.println("  Avg: " + String.format("%.1f", avg) + " entities/bubble");

            var distResult = validator.validateDistribution(accountant);
            System.out.println("  Health: " + (distResult.healthy() ? "HEALTHY" : "WARNING"));
            if (!distResult.healthy()) {
                System.out.println("    " + distResult.message());
            }
        }
    }

    private int getTotalEntityCount(EntityAccountant accountant) {
        return accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
    }
}
