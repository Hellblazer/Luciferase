/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
import com.hellblazer.luciferase.simulation.topology.metrics.DensityMonitor;
import com.hellblazer.luciferase.simulation.topology.metrics.DensityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for merge functionality.
 * <p>
 * Validates the end-to-end merge path:
 * <ol>
 *   <li>Density monitor detects NEEDS_MERGE state</li>
 *   <li>Neighbor detection finds merge candidates</li>
 *   <li>TopologyExecutor executes merge via BubbleMerger</li>
 *   <li>Entity conservation validated</li>
 *   <li>Bubble count decreases</li>
 * </ol>
 * <p>
 * Part of P3: Merge Implementation Testing (bead: Luciferase-fuup).
 *
 * @author hal.hildebrand
 */
class MergeIntegrationTest {
    private static final TestClock JC1KH_CLOCK = new TestClock(1_000L); // determinism mandate (Luciferase-jc1kh)

    private static final Logger log = LoggerFactory.getLogger(MergeIntegrationTest.class);

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics metrics;
    private TopologyExecutor executor;
    private DensityMonitor densityMonitor;

    @BeforeEach
    void setup() {
        // Create grid with level 2 (allows adjacent bubbles)
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
        executor = new TopologyExecutor(bubbleGrid, accountant, metrics);

        // Merge threshold: 100 entities
        densityMonitor = new DensityMonitor(250, 100);
    }

    @Test
    void testMergeTriggers_whenTwoBubblesNeedMerge() {
        // Create bubbles at level 2 (more keys available)
        bubbleGrid.createBubbles(4, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        // May create fewer than requested due to tetrahedral geometry
        assertThat(bubbles.size()).isGreaterThanOrEqualTo(2);

        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add few entities to bubble1 (below merge threshold)
        addEntities(bubble1.id(), 50);

        // Add few entities to bubble2 (below merge threshold)
        addEntities(bubble2.id(), 40);

        // Update density monitor
        var distribution = accountant.getDistribution();
        densityMonitor.update(distribution);

        // Verify both bubbles are in NEEDS_MERGE state
        assertThat(densityMonitor.getState(bubble1.id()))
            .as("Bubble1 should need merge with 50 entities")
            .isEqualTo(DensityState.NEEDS_MERGE);
        assertThat(densityMonitor.getState(bubble2.id()))
            .as("Bubble2 should need merge with 40 entities")
            .isEqualTo(DensityState.NEEDS_MERGE);

        int totalEntitiesBefore = accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        log.info("Before merge: {} bubbles, {} total entities", bubbleCountBefore, totalEntitiesBefore);

        // Execute merge directly (in real system, neighbor check would happen first)
        // This test validates the merge execution path works correctly
        var mergeProposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        log.info("Executing merge: {} + {}", bubble1.id(), bubble2.id());
        var result = executor.execute(mergeProposal);

        // RDR-018 AC-4: an arbitrary two-bubble merge is fenced because removing bubble2 would
        // leave its tetrahedral region untiled (coverage hole). The executor must report failure
        // and leave the partition fully intact.
        assertThat(result.success())
            .as("Arbitrary two-bubble merge must be fenced (AC-4): %s", result.message())
            .isFalse();
        log.info("Merge result: {}", result.message());

        // Verify entity conservation — the fenced merge mutates nothing
        int totalEntitiesAfter = accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(totalEntitiesAfter)
            .as("Total entities must be conserved when a merge is fenced")
            .isEqualTo(totalEntitiesBefore);

        // Verify bubble count is UNCHANGED — no bubble removed, so no coverage hole
        int bubbleCountAfter = bubbleGrid.getAllBubbles().size();
        assertThat(bubbleCountAfter)
            .as("Bubble count must be unchanged when a merge is fenced (no region untiled)")
            .isEqualTo(bubbleCountBefore);

        log.info("After fenced merge: {} bubbles, {} total entities", bubbleCountAfter, totalEntitiesAfter);

        // Verify validation passes
        var validation = accountant.validate();
        assertThat(validation.success())
            .as("Entity validation should pass after fenced merge")
            .isTrue();
    }

    @Test
    void testMergeWithEmptyBubble_succeeds() {
        // Create 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        assertThat(bubbles.size()).isGreaterThanOrEqualTo(2);

        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add entities only to bubble1 (below merge threshold)
        addEntities(bubble1.id(), 80);
        // bubble2 has no entities - need to include it in distribution for density monitor

        // Update density monitor - must include all bubbles even with 0 entities
        var distribution = new HashMap<>(accountant.getDistribution());
        distribution.putIfAbsent(bubble2.id(), 0);  // Ensure empty bubble is tracked
        densityMonitor.update(distribution);

        // bubble1 with 80 entities should be NEEDS_MERGE (< 100 threshold)
        // bubble2 with 0 entities should be NEEDS_MERGE
        assertThat(densityMonitor.getState(bubble1.id())).isEqualTo(DensityState.NEEDS_MERGE);
        assertThat(densityMonitor.getState(bubble2.id())).isEqualTo(DensityState.NEEDS_MERGE);

        int totalBefore = accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();

        // Execute merge
        var mergeProposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        var result = executor.execute(mergeProposal);

        // RDR-018 AC-4: even a merge with an empty bubble2 is fenced — removing bubble2 still
        // untiles its region. The empty-bubble case is no longer a special-cased success.
        assertThat(result.success())
            .as("Merge with empty bubble must be fenced (AC-4)")
            .isFalse();

        // Entity count unchanged
        assertThat(accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(totalBefore);

        // Bubble count unchanged — bubble2 not removed, region stays tiled
        assertThat(bubbleGrid.getAllBubbles()).hasSize(2);

        log.info("Fenced empty-bubble merge, total entities: {}", totalBefore);
    }

    @Test
    void testMergePreservesEntityData() {
        // Create 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();

        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add entities with specific content
        var entityIds1 = addEntitiesWithContent(bubble1.id(), 30, "group-A");
        var entityIds2 = addEntitiesWithContent(bubble2.id(), 20, "group-B");

        densityMonitor.update(accountant.getDistribution());

        // Execute merge
        var mergeProposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        var result = executor.execute(mergeProposal);

        // RDR-018 AC-4: merge is fenced, so no entity is relocated — each group's entities stay
        // in their original bubble and no data is lost.
        assertThat(result.success()).as("Merge must be fenced (AC-4): %s", result.message()).isFalse();

        var entitiesInBubble1 = accountant.entitiesInBubble(bubble1.id());
        var entitiesInBubble2 = accountant.entitiesInBubble(bubble2.id());
        assertThat(entitiesInBubble1).hasSize(30);
        assertThat(entitiesInBubble2).hasSize(20);

        // group-A entities remain in bubble1
        for (var entityId : entityIds1) {
            assertThat(entitiesInBubble1).contains(entityId);
        }
        // group-B entities remain in bubble2 (NOT moved — merge fenced)
        for (var entityId : entityIds2) {
            assertThat(entitiesInBubble2).contains(entityId);
        }

        log.info("Fenced merge preserved all 50 entities in their original bubbles");
    }

    @Test
    void testMergeUpdatesMetrics() {
        // Create 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();

        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1.id(), 60);
        addEntities(bubble2.id(), 40);

        long mergesBefore = metrics.getTotalMerges();
        long failuresBefore = metrics.getMetrics().getOrDefault("topology_merges_failed_total", 0L);

        // Execute merge
        var mergeProposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        executor.execute(mergeProposal);

        // The merge is fenced (AC-4): it is counted as an attempted merge that FAILED.
        long mergesAfter = metrics.getTotalMerges();
        assertThat(mergesAfter)
            .as("Merge attempt should still increment the total (success + failed)")
            .isEqualTo(mergesBefore + 1);
        assertThat(metrics.getMetrics().get("topology_merges_failed_total"))
            .as("A fenced merge must be recorded as a failure, not a success")
            .isEqualTo(failuresBefore + 1);
    }

    @Test
    void testDensityMonitorDetectsNeedsMerge() {
        // Create bubbles with varying entity counts - use level 2 for more bubbles
        bubbleGrid.createBubbles(4, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();

        // Work with however many bubbles were created
        int bubbleCount = bubbles.size();
        log.info("Created {} bubbles for density test", bubbleCount);
        assertThat(bubbleCount).isGreaterThanOrEqualTo(2);

        // Set up different entity counts based on available bubbles
        if (bubbleCount >= 1) addEntities(bubbles.get(0).id(), 300);  // NEEDS_SPLIT
        if (bubbleCount >= 2) addEntities(bubbles.get(1).id(), 50);   // NEEDS_MERGE

        densityMonitor.update(accountant.getDistribution());

        // Verify density states for available bubbles
        assertThat(densityMonitor.getState(bubbles.get(0).id())).isEqualTo(DensityState.NEEDS_SPLIT);
        if (bubbleCount >= 2) {
            assertThat(densityMonitor.getState(bubbles.get(1).id())).isEqualTo(DensityState.NEEDS_MERGE);
        }

        log.info("Density states correctly detected for {} bubbles", bubbleCount);
    }

    // ========== Helper Methods ==========

    private void addEntities(UUID bubbleId, int count) {
        var bubble = bubbleGrid.getBubbleById(bubbleId);
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            var position = new Point3f(i * 0.01f, i * 0.01f, i * 0.01f);
            bubble.addEntity(entityId.toString(), position, null);
            accountant.register(bubbleId, entityId);
        }
    }

    private java.util.List<UUID> addEntitiesWithContent(UUID bubbleId, int count, String contentPrefix) {
        var bubble = bubbleGrid.getBubbleById(bubbleId);
        var entityIds = new java.util.ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            var position = new Point3f(i * 0.01f, i * 0.01f, i * 0.01f);
            bubble.addEntity(entityId.toString(), position, contentPrefix + "-" + i);
            accountant.register(bubbleId, entityId);
        }
        return entityIds;
    }
}
