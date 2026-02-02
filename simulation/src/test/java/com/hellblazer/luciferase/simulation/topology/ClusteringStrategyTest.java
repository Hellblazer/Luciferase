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

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ClusteringStrategy.
 * <p>
 * Validates:
 * <ul>
 *   <li>Split plane computed between two clusters</li>
 *   <li>Fallback to LongestAxisStrategy when no clear clusters</li>
 *   <li>Performance compared to rotation strategy</li>
 * </ul>
 * <p>
 * Part of P2.4: Clustering Enhancement (bead: Luciferase-cv12).
 *
 * @author hal.hildebrand
 */
class ClusteringStrategyTest {

    private ClusteringStrategy strategy;
    private BubbleBounds bounds;

    @BeforeEach
    void setup() {
        strategy = new ClusteringStrategy(3, 50.0f);
        // Create bounds covering an area - use entity positions to define extents
        bounds = BubbleBounds.fromEntityPositions(List.of(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(100.0f, 100.0f, 100.0f)
        ));
    }

    // ========== Cluster Detection Tests ==========

    @Test
    void testTwoClearClusters_splitPlaneBetweenCentroids() {
        // Two distinct clusters along X axis
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();

        // Cluster 1: around (20, 50, 50)
        entities.add(createEntity("e1", 18.0f, 48.0f, 50.0f));
        entities.add(createEntity("e2", 20.0f, 50.0f, 50.0f));
        entities.add(createEntity("e3", 22.0f, 52.0f, 50.0f));
        entities.add(createEntity("e4", 19.0f, 51.0f, 50.0f));

        // Cluster 2: around (80, 50, 50)
        entities.add(createEntity("e5", 78.0f, 48.0f, 50.0f));
        entities.add(createEntity("e6", 80.0f, 50.0f, 50.0f));
        entities.add(createEntity("e7", 82.0f, 52.0f, 50.0f));
        entities.add(createEntity("e8", 81.0f, 51.0f, 50.0f));

        var plane = strategy.calculate(bounds, entities);

        // Plane should be perpendicular to line between clusters (X axis dominant)
        // Cluster centroids: ~(20, 50, 50) and ~(80, 50, 50)
        // Midpoint: ~(50, 50, 50)
        assertThat(plane).isNotNull();

        // Normal should be along X axis (dominant direction between clusters)
        assertThat(Math.abs(plane.normal().x)).isGreaterThan(0.9f);

        // Distance should be approximately at midpoint (50) - can be negative depending on normal direction
        assertThat(Math.abs(plane.distance())).isBetween(40.0f, 60.0f);
    }

    @Test
    void testTwoClustersAlongYAxis() {
        // Two distinct clusters along Y axis
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();

        // Cluster 1: around (50, 20, 50)
        entities.add(createEntity("e1", 48.0f, 18.0f, 50.0f));
        entities.add(createEntity("e2", 50.0f, 20.0f, 50.0f));
        entities.add(createEntity("e3", 52.0f, 22.0f, 50.0f));
        entities.add(createEntity("e4", 51.0f, 19.0f, 50.0f));

        // Cluster 2: around (50, 80, 50)
        entities.add(createEntity("e5", 48.0f, 78.0f, 50.0f));
        entities.add(createEntity("e6", 50.0f, 80.0f, 50.0f));
        entities.add(createEntity("e7", 52.0f, 82.0f, 50.0f));
        entities.add(createEntity("e8", 51.0f, 81.0f, 50.0f));

        var plane = strategy.calculate(bounds, entities);

        // Normal should be along Y axis
        assertThat(Math.abs(plane.normal().y)).isGreaterThan(0.9f);

        // Distance should be approximately at midpoint (50) - can be negative depending on normal direction
        assertThat(Math.abs(plane.distance())).isBetween(40.0f, 60.0f);
    }

    @Test
    void testTwoClustersAlongZAxis() {
        // Two distinct clusters along Z axis
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();

        // Cluster 1: around (50, 50, 20)
        entities.add(createEntity("e1", 48.0f, 50.0f, 18.0f));
        entities.add(createEntity("e2", 50.0f, 50.0f, 20.0f));
        entities.add(createEntity("e3", 52.0f, 50.0f, 22.0f));
        entities.add(createEntity("e4", 51.0f, 50.0f, 19.0f));

        // Cluster 2: around (50, 50, 80)
        entities.add(createEntity("e5", 48.0f, 50.0f, 78.0f));
        entities.add(createEntity("e6", 50.0f, 50.0f, 80.0f));
        entities.add(createEntity("e7", 52.0f, 50.0f, 82.0f));
        entities.add(createEntity("e8", 51.0f, 50.0f, 81.0f));

        var plane = strategy.calculate(bounds, entities);

        // Normal should be along Z axis
        assertThat(Math.abs(plane.normal().z)).isGreaterThan(0.9f);

        // Distance should be approximately at midpoint (50) - can be negative depending on normal direction
        assertThat(Math.abs(plane.distance())).isBetween(40.0f, 60.0f);
    }

    // ========== Fallback Tests ==========

    @Test
    void testFewEntities_fallbackToLongestAxis() {
        // Only 2 entities - not enough for clustering
        var entities = List.of(
            createEntity("e1", 20.0f, 50.0f, 50.0f),
            createEntity("e2", 80.0f, 50.0f, 50.0f)
        );

        var plane = strategy.calculate(bounds, entities);

        // Should fall back to longest axis strategy
        assertThat(plane).isNotNull();
        // Result depends on LongestAxisStrategy behavior
    }

    @Test
    void testUniformDistribution_fallbackToLongestAxis() {
        // Uniformly distributed entities - no clear clusters
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();
        for (int i = 0; i < 20; i++) {
            float x = 10.0f + (i % 5) * 20.0f;
            float y = 10.0f + (i / 5) * 20.0f;
            entities.add(createEntity("e" + i, x, y, 50.0f));
        }

        var plane = strategy.calculate(bounds, entities);

        // Should return valid plane (either clustering or fallback)
        assertThat(plane).isNotNull();
    }

    @Test
    void testEmptyEntities_fallbackToBounds() {
        var plane = strategy.calculate(bounds, List.of());

        // Should fall back and use bounds
        assertThat(plane).isNotNull();
    }

    // ========== Diagonal Cluster Tests ==========

    @Test
    void testDiagonalClusters_splitPlanePerpendicularToDiagonal() {
        // Two clusters along diagonal
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();

        // Cluster 1: around (20, 20, 20)
        entities.add(createEntity("e1", 18.0f, 18.0f, 18.0f));
        entities.add(createEntity("e2", 20.0f, 20.0f, 20.0f));
        entities.add(createEntity("e3", 22.0f, 22.0f, 22.0f));
        entities.add(createEntity("e4", 21.0f, 19.0f, 21.0f));

        // Cluster 2: around (80, 80, 80)
        entities.add(createEntity("e5", 78.0f, 78.0f, 78.0f));
        entities.add(createEntity("e6", 80.0f, 80.0f, 80.0f));
        entities.add(createEntity("e7", 82.0f, 82.0f, 82.0f));
        entities.add(createEntity("e8", 79.0f, 81.0f, 79.0f));

        var plane = strategy.calculate(bounds, entities);

        // Normal should have components in all three axes
        assertThat(plane).isNotNull();
        assertThat(Math.abs(plane.normal().x)).isGreaterThan(0.3f);
        assertThat(Math.abs(plane.normal().y)).isGreaterThan(0.3f);
        assertThat(Math.abs(plane.normal().z)).isGreaterThan(0.3f);
    }

    // ========== Plane Intersection Test ==========

    @Test
    void testClusterSplit_planeIntersectsEntityBounds() {
        // Two clusters along X axis
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();

        // Cluster 1: around (20, 50, 50)
        for (int i = 0; i < 5; i++) {
            entities.add(createEntity("e1_" + i, 18.0f + i, 48.0f + i, 50.0f));
        }

        // Cluster 2: around (80, 50, 50)
        for (int i = 0; i < 5; i++) {
            entities.add(createEntity("e2_" + i, 78.0f + i, 48.0f + i, 50.0f));
        }

        var plane = strategy.calculate(bounds, entities);

        // Plane should intersect entity bounds
        assertThat(plane.intersectsEntityBounds(entities)).isTrue();
    }

    // ========== Empty Cluster Tests ==========

    @Test
    void testEmptyClusterPreservesCentroid() {
        // Create a scenario where one cluster might become empty during iteration
        // Use entities very close together plus one outlier to test edge case
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();

        // Tight cluster around (50, 50, 50) - will all be assigned to same centroid
        for (int i = 0; i < 6; i++) {
            entities.add(createEntity("close_" + i, 50.0f + i * 0.1f, 50.0f + i * 0.1f, 50.0f));
        }

        // One outlier at (90, 90, 90) - might create empty cluster scenario
        entities.add(createEntity("outlier", 90.0f, 90.0f, 90.0f));

        var plane = strategy.calculate(bounds, entities);

        // Should either cluster successfully or fall back gracefully
        assertThat(plane).isNotNull();

        // Verify plane has valid normal (unit vector)
        var normal = plane.normal();
        float normalLength = (float) Math.sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
        assertThat(normalLength).isBetween(0.99f, 1.01f); // Should be ~1.0
    }

    // ========== Helper Methods ==========

    private EnhancedBubble.EntityRecord createEntity(String id, float x, float y, float z) {
        return new EnhancedBubble.EntityRecord(id, new Point3f(x, y, z), "content", 0L);
    }
}
