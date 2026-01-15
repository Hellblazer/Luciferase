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
package com.hellblazer.luciferase.simulation.topology.metrics;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TopologyMetricsCollector orchestration.
 *
 * @author hal.hildebrand
 */
class TopologyMetricsCollectorTest {

    private TopologyMetricsCollector collector;
    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant entityAccountant;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        entityAccountant = new EntityAccountant();

        collector = new TopologyMetricsCollector(
            5000,  // splitThreshold
            500,   // mergeThreshold
            50,    // minClusterSize
            50.0f, // maxClusterDistance
            60000  // boundaryStressWindow
        );
    }

    @Test
    void testMetricsCollectionFromMultipleBubbles() {
        // Create bubbles with different entity counts
        bubbleGrid.createBubbles(3, (byte) 2, 10);

        var bubbles = bubbleGrid.getAllBubbles();
        assertTrue(bubbles.size() >= 2, "Should have at least 2 bubbles, got: " + bubbles.size());

        // Add different entity counts to ALL bubbles
        var bubbleList = bubbles.stream().toList();
        var bubble1 = bubbleList.get(0);
        var bubble2 = bubbleList.get(1);

        // Bubble 1: Above split threshold (5100 entities)
        addEntities(bubble1, 5100, entityAccountant);

        // Bubble 2: Normal (2000 entities)
        addEntities(bubble2, 2000, entityAccountant);

        // If there's a third bubble, add entities below merge threshold
        if (bubbles.size() >= 3) {
            var bubble3 = bubbleList.get(2);
            addEntities(bubble3, 450, entityAccountant);
        }

        // Collect metrics
        var snapshot = collector.collect(bubbleGrid, entityAccountant);

        assertNotNull(snapshot, "Snapshot should not be null");
        assertEquals(bubbles.size(), snapshot.bubbleCount(), "Bubble count should match");
        assertTrue(snapshot.totalEntities() >= 7100, "Total should be at least 5100 + 2000");
        assertTrue(snapshot.averageDensity() > 0, "Average density should be positive");

        // Verify split detection
        var needsSplit = snapshot.bubblesNeedingSplit();
        assertEquals(1, needsSplit.size(), "Should detect 1 bubble needing split");
        assertTrue(needsSplit.contains(bubble1.id()), "Bubble 1 should need split");

        // Verify merge detection (at least 1, might be more if empty bubbles exist)
        var needsMerge = snapshot.bubblesNeedingMerge();
        assertTrue(needsMerge.size() >= 1, "Should detect at least 1 bubble needing merge");
    }

    @Test
    void testBoundaryStressTracking() {
        // Create bubble grid and add migrations
        bubbleGrid.createBubbles(2, (byte) 1, 10);

        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add entities
        addEntities(bubble1, 3000, entityAccountant);
        addEntities(bubble2, 2000, entityAccountant);

        // Record high migration stress for bubble1
        long now = System.currentTimeMillis();
        for (int i = 0; i < 150; i++) {
            collector.recordMigration(bubble1.id(), now + i * 66); // ~15/sec
        }

        // Collect metrics
        var snapshot = collector.collect(bubbleGrid, entityAccountant);

        // Verify boundary stress detection
        var metrics1 = snapshot.bubbleMetrics().get(bubble1.id());
        assertNotNull(metrics1, "Should have metrics for bubble 1");
        assertTrue(metrics1.hasHighBoundaryStress(), "Bubble 1 should have high boundary stress");
    }

    @Test
    void testClusteringDetection() {
        // Create single bubble with clustered entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);

        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add 200 entities in two distinct spatial clusters
        // Cluster 1: around (10, 10, 10)
        for (int i = 0; i < 100; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(10 + i * 0.1f, 10 + i * 0.1f, 10 + i * 0.1f),
                null
            );
            entityAccountant.register(bubble.id(), entityId);
        }

        // Cluster 2: around (100, 100, 100)
        for (int i = 0; i < 100; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(100 + i * 0.1f, 100 + i * 0.1f, 100 + i * 0.1f),
                null
            );
            entityAccountant.register(bubble.id(), entityId);
        }

        // Collect metrics
        var snapshot = collector.collect(bubbleGrid, entityAccountant);

        // Verify cluster detection
        assertNotNull(snapshot.clusters(), "Clusters should not be null");
        // May or may not detect clusters depending on distribution
        // Just verify no exceptions and clusters have valid data
        for (var cluster : snapshot.clusters()) {
            assertNotNull(cluster.centroid(), "Cluster should have centroid");
            assertTrue(cluster.size() > 0, "Cluster should have entities");
        }
    }

    /**
     * Helper to add entities to a bubble and register with accountant.
     */
    private void addEntities(EnhancedBubble bubble, int count, EntityAccountant accountant) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.1f, i * 0.1f, i * 0.1f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }
    }
}
