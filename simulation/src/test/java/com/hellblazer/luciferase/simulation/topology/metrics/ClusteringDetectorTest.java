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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClusteringDetector wrapping AdaptiveSplitPolicy.
 *
 * @author hal.hildebrand
 */
class ClusteringDetectorTest {

    private ClusteringDetector detector;
    private EnhancedBubble bubble;

    @BeforeEach
    void setUp() {
        detector = new ClusteringDetector(50, 50.0f); // minClusterSize=50, maxDistance=50.0
        bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);
    }

    @Test
    void testClusterDetectionWithSufficientEntities() {
        // Add 200 entities in two distinct clusters
        // Cluster 1: around (10, 10, 10)
        for (int i = 0; i < 100; i++) {
            bubble.addEntity(
                "entity-cluster1-" + i,
                new Point3f(10 + i * 0.1f, 10 + i * 0.1f, 10 + i * 0.1f),
                null
            );
        }

        // Cluster 2: around (100, 100, 100)
        for (int i = 0; i < 100; i++) {
            bubble.addEntity(
                "entity-cluster2-" + i,
                new Point3f(100 + i * 0.1f, 100 + i * 0.1f, 100 + i * 0.1f),
                null
            );
        }

        var clusters = detector.detectClusters(bubble);

        assertFalse(clusters.isEmpty(), "Should detect clusters with 200 entities");
        assertTrue(clusters.size() <= 2, "Should detect at most 2 clusters");

        // Verify cluster properties
        for (var cluster : clusters) {
            assertNotNull(cluster.centroid(), "Cluster should have centroid");
            assertFalse(cluster.entityIds().isEmpty(), "Cluster should have entities");
            assertTrue(cluster.coherence() >= 0.0f && cluster.coherence() <= 1.0f,
                      "Coherence should be in [0.0, 1.0]");
            assertEquals(cluster.entityIds().size(), cluster.size(), "Size should match entity count");
        }
    }

    @Test
    void testNoClusterDetectionWithFewEntities() {
        // Add only 30 entities (below minClusterSize * 2 = 100)
        for (int i = 0; i < 30; i++) {
            bubble.addEntity(
                "entity-" + i,
                new Point3f(i * 1.0f, i * 1.0f, i * 1.0f),
                null
            );
        }

        var clusters = detector.detectClusters(bubble);

        assertTrue(clusters.isEmpty(), "Should not detect clusters with too few entities");
    }

    @Test
    void testCoherenceCalculation() {
        // Create a tight cluster (high coherence)
        for (int i = 0; i < 100; i++) {
            // Entities very close together
            bubble.addEntity(
                "tight-entity-" + i,
                new Point3f(50 + i * 0.01f, 50 + i * 0.01f, 50 + i * 0.01f),
                null
            );
        }

        var clusters = detector.detectClusters(bubble);

        if (!clusters.isEmpty()) {
            var cluster = clusters.getFirst();
            // Tight cluster should have high coherence (>0.5)
            assertTrue(cluster.coherence() > 0.5f,
                      "Tight cluster should have coherence > 0.5, got: " + cluster.coherence());
        }
    }

    @Test
    void testMultipleBubbleAnalysis() {
        var bubble1 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);
        var bubble2 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);

        // Bubble 1: Two distinct clusters
        for (int i = 0; i < 100; i++) {
            bubble1.addEntity("b1-c1-" + i, new Point3f(10 + i * 0.1f, 10, 10), null);
        }
        for (int i = 0; i < 100; i++) {
            bubble1.addEntity("b1-c2-" + i, new Point3f(100 + i * 0.1f, 100, 100), null);
        }

        // Bubble 2: Uniform distribution (no clear clusters)
        for (int i = 0; i < 100; i++) {
            bubble2.addEntity("b2-uniform-" + i, new Point3f(i * 2.0f, i * 2.0f, i * 2.0f), null);
        }

        var clusters1 = detector.detectClusters(bubble1);
        var clusters2 = detector.detectClusters(bubble2);

        // Bubble 1 should detect clusters
        assertFalse(clusters1.isEmpty(), "Bubble 1 should detect clusters");

        // Bubble 2 might or might not detect clusters (depends on distribution)
        // Just verify it doesn't crash
        assertNotNull(clusters2, "Bubble 2 should return non-null result");
    }

    @Test
    void testEmptyBubble() {
        var emptyBubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);

        var clusters = detector.detectClusters(emptyBubble);

        assertTrue(clusters.isEmpty(), "Empty bubble should have no clusters");
    }
}
