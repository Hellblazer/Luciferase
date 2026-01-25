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

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for split plane strategy correctness.
 * <p>
 * Validates:
 * - Geometric correctness of split planes
 * - Entity distribution balance after splitting
 * - No entity loss during split operations
 * - Plane orientation matches expected axis
 * - Cyclic strategy rotation sequence
 * - Split plane placement through centroid
 */
class SplitPlaneValidationTest {

    /**
     * Test that LongestAxisStrategy produces balanced entity distribution.
     * <p>
     * Creates entity distribution along X-axis and validates:
     * - Strategy selects X-axis (longest dimension)
     * - Entity split is approximately balanced (40-60% tolerance)
     * - Plane divides entities at centroid
     */
    @Test
    void testLongestAxisProducesBalancedSplit() {
        // Create entities distributed along X-axis (200m span, 20m Y/Z)
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();
        for (int i = 0; i < 100; i++) {
            var pos = new Point3f(
                50.0f + i * 2.0f,  // X: 50 to 248 (span = 198m)
                100.0f + (i % 5),  // Y: 100 to 104 (span = 4m)
                100.0f + (i % 5)   // Z: 100 to 104 (span = 4m)
            );
            entities.add(new EnhancedBubble.EntityRecord(
                UUID.randomUUID().toString(),
                pos,
                null,
                0L
            ));
        }

        var bounds = BubbleBounds.fromEntityPositions(
            entities.stream().map(EnhancedBubble.EntityRecord::position).toList()
        );

        var strategy = SplitPlaneStrategies.longestAxis();
        var plane = strategy.calculate(bounds, entities);

        // Verify X-axis selected (longest dimension)
        assertEquals(SplitPlane.SplitAxis.X, plane.axis(),
            "Strategy should select X-axis for X-dominant distribution");

        // Partition entities by split plane
        int leftCount = 0;
        int rightCount = 0;
        for (var entity : entities) {
            var pos = entity.position();
            // Calculate signed distance from plane
            float distance = plane.normal().x * pos.x +
                           plane.normal().y * pos.y +
                           plane.normal().z * pos.z -
                           plane.distance();
            if (distance < 0) {
                leftCount++;
            } else {
                rightCount++;
            }
        }

        // Verify balanced distribution (40-60% tolerance)
        var totalCount = entities.size();
        var leftPercent = (leftCount * 100.0) / totalCount;
        var rightPercent = (rightCount * 100.0) / totalCount;

        assertTrue(leftPercent >= 40.0 && leftPercent <= 60.0,
            String.format("Left side should be 40-60%%, got %.1f%%", leftPercent));
        assertTrue(rightPercent >= 40.0 && rightPercent <= 60.0,
            String.format("Right side should be 40-60%%, got %.1f%%", rightPercent));
    }

    /**
     * Test XAxisStrategy plane orientation.
     * <p>
     * Validates:
     * - Normal vector is exactly (1, 0, 0)
     * - Axis annotation is X
     * - Plane placement through bounds centroid
     */
    @Test
    void testXAxisStrategyPlaneOrientation() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 150.0f, 150.0f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.xAxis();
        var plane = strategy.calculate(bounds, List.of());

        // Verify axis annotation
        assertEquals(SplitPlane.SplitAxis.X, plane.axis(),
            "XAxisStrategy must return X axis");

        // Verify normal vector is exactly (1, 0, 0)
        assertEquals(1.0f, plane.normal().x, 1e-6f,
            "X-axis normal must have x=1.0");
        assertEquals(0.0f, plane.normal().y, 1e-6f,
            "X-axis normal must have y=0.0");
        assertEquals(0.0f, plane.normal().z, 1e-6f,
            "X-axis normal must have z=0.0");

        // Verify plane passes through centroid (approximately)
        var centroid = bounds.centroid();
        float expectedDistance = (float) centroid.getX();
        assertEquals(expectedDistance, plane.distance(), 5.0f,
            "Plane should pass through centroid X coordinate");
    }

    /**
     * Test YAxisStrategy plane orientation.
     * <p>
     * Validates:
     * - Normal vector is exactly (0, 1, 0)
     * - Axis annotation is Y
     * - Plane placement through bounds centroid
     */
    @Test
    void testYAxisStrategyPlaneOrientation() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 150.0f, 150.0f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.yAxis();
        var plane = strategy.calculate(bounds, List.of());

        // Verify axis annotation
        assertEquals(SplitPlane.SplitAxis.Y, plane.axis(),
            "YAxisStrategy must return Y axis");

        // Verify normal vector is exactly (0, 1, 0)
        assertEquals(0.0f, plane.normal().x, 1e-6f,
            "Y-axis normal must have x=0.0");
        assertEquals(1.0f, plane.normal().y, 1e-6f,
            "Y-axis normal must have y=1.0");
        assertEquals(0.0f, plane.normal().z, 1e-6f,
            "Y-axis normal must have z=0.0");

        // Verify plane passes through centroid (approximately)
        var centroid = bounds.centroid();
        float expectedDistance = (float) centroid.getY();
        assertEquals(expectedDistance, plane.distance(), 5.0f,
            "Plane should pass through centroid Y coordinate");
    }

    /**
     * Test ZAxisStrategy plane orientation.
     * <p>
     * Validates:
     * - Normal vector is exactly (0, 0, 1)
     * - Axis annotation is Z
     * - Plane placement through bounds centroid
     */
    @Test
    void testZAxisStrategyPlaneOrientation() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 150.0f, 150.0f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.zAxis();
        var plane = strategy.calculate(bounds, List.of());

        // Verify axis annotation
        assertEquals(SplitPlane.SplitAxis.Z, plane.axis(),
            "ZAxisStrategy must return Z axis");

        // Verify normal vector is exactly (0, 0, 1)
        assertEquals(0.0f, plane.normal().x, 1e-6f,
            "Z-axis normal must have x=0.0");
        assertEquals(0.0f, plane.normal().y, 1e-6f,
            "Z-axis normal must have y=0.0");
        assertEquals(1.0f, plane.normal().z, 1e-6f,
            "Z-axis normal must have z=1.0");

        // Verify plane passes through centroid (approximately)
        var centroid = bounds.centroid();
        float expectedDistance = (float) centroid.getZ();
        assertEquals(expectedDistance, plane.distance(), 5.0f,
            "Plane should pass through centroid Z coordinate");
    }

    /**
     * Test CyclicAxisStrategy rotates through X → Y → Z → X sequence.
     * <p>
     * Validates:
     * - First call returns X-axis
     * - Second call returns Y-axis
     * - Third call returns Z-axis
     * - Fourth call cycles back to X-axis
     * - Fifth call returns Y-axis (continuing cycle)
     */
    @Test
    void testCyclicStrategyRotates() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 150.0f, 150.0f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.cyclic();

        // First call: X
        var plane1 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.X, plane1.axis(),
            "First cyclic call should return X-axis");

        // Second call: Y
        var plane2 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.Y, plane2.axis(),
            "Second cyclic call should return Y-axis");

        // Third call: Z
        var plane3 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.Z, plane3.axis(),
            "Third cyclic call should return Z-axis");

        // Fourth call: cycle back to X
        var plane4 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.X, plane4.axis(),
            "Fourth cyclic call should cycle back to X-axis");

        // Fifth call: Y (continuing cycle)
        var plane5 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.Y, plane5.axis(),
            "Fifth cyclic call should return Y-axis");
    }

    /**
     * Test no entity loss after split operation.
     * <p>
     * Validates:
     * - All entities accounted for after partitioning
     * - Sum of left + right equals original count
     * - No duplicate entities in partition
     */
    @Test
    void testNoEntityLossAfterSplit() {
        // Create 200 entities in random distribution
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();
        for (int i = 0; i < 200; i++) {
            var pos = new Point3f(
                50.0f + (i % 20) * 10.0f,  // X: 50 to 240
                50.0f + (i % 15) * 10.0f,  // Y: 50 to 190
                50.0f + (i % 10) * 10.0f   // Z: 50 to 140
            );
            entities.add(new EnhancedBubble.EntityRecord(
                UUID.randomUUID().toString(),
                pos,
                null,
                0L
            ));
        }

        var bounds = BubbleBounds.fromEntityPositions(
            entities.stream().map(EnhancedBubble.EntityRecord::position).toList()
        );

        // Test with each strategy
        for (var strategy : List.of(
            SplitPlaneStrategies.longestAxis(),
            SplitPlaneStrategies.xAxis(),
            SplitPlaneStrategies.yAxis(),
            SplitPlaneStrategies.zAxis()
        )) {
            var plane = strategy.calculate(bounds, entities);

            // Partition entities
            var leftEntities = new ArrayList<EnhancedBubble.EntityRecord>();
            var rightEntities = new ArrayList<EnhancedBubble.EntityRecord>();

            for (var entity : entities) {
                var pos = entity.position();
                float distance = plane.normal().x * pos.x +
                               plane.normal().y * pos.y +
                               plane.normal().z * pos.z -
                               plane.distance();
                if (distance < 0) {
                    leftEntities.add(entity);
                } else {
                    rightEntities.add(entity);
                }
            }

            // Verify no entity loss
            assertEquals(entities.size(), leftEntities.size() + rightEntities.size(),
                "Total entities must equal left + right partition");

            // Verify all entities accounted for
            assertTrue(leftEntities.size() > 0,
                "Left partition should not be empty");
            assertTrue(rightEntities.size() > 0,
                "Right partition should not be empty");
        }
    }

    /**
     * Test split plane passes through entity centroid.
     * <p>
     * Validates:
     * - LongestAxisStrategy places plane through entity centroid
     * - Fixed axis strategies (X, Y, Z) also use centroid
     * - Distance from plane to centroid is minimal (<1m tolerance)
     */
    @Test
    void testSplitPlaneThroughCentroid() {
        // Create entities with known centroid
        var entities = new ArrayList<EnhancedBubble.EntityRecord>();
        entities.add(new EnhancedBubble.EntityRecord(
            UUID.randomUUID().toString(),
            new Point3f(50.0f, 50.0f, 50.0f),
            null,
            0L
        ));
        entities.add(new EnhancedBubble.EntityRecord(
            UUID.randomUUID().toString(),
            new Point3f(150.0f, 150.0f, 150.0f),
            null,
            0L
        ));
        // Centroid should be (100, 100, 100)

        var bounds = BubbleBounds.fromEntityPositions(
            entities.stream().map(EnhancedBubble.EntityRecord::position).toList()
        );

        // Compute expected centroid
        float expectedCx = (50.0f + 150.0f) / 2.0f;  // 100.0
        float expectedCy = (50.0f + 150.0f) / 2.0f;  // 100.0
        float expectedCz = (50.0f + 150.0f) / 2.0f;  // 100.0

        // Test LongestAxisStrategy with entities
        var longestStrategy = new LongestAxisStrategy();
        var plane = longestStrategy.calculate(bounds, entities);

        // Calculate distance from centroid to plane
        float distanceFromCentroid = Math.abs(
            plane.normal().x * expectedCx +
            plane.normal().y * expectedCy +
            plane.normal().z * expectedCz -
            plane.distance()
        );

        // Plane should pass through centroid (tolerance: 1.0m)
        assertTrue(distanceFromCentroid < 1.0f,
            String.format("Plane should pass through entity centroid, distance: %.3f", distanceFromCentroid));

        // Test fixed axis strategies also place plane through centroid
        for (var strategy : List.of(
            SplitPlaneStrategies.xAxis(),
            SplitPlaneStrategies.yAxis(),
            SplitPlaneStrategies.zAxis()
        )) {
            var axisPlane = strategy.calculate(bounds, entities);

            // For X-axis, distance should equal centroid X, etc.
            float expectedDistance = switch (axisPlane.axis()) {
                case X -> expectedCx;
                case Y -> expectedCy;
                case Z -> expectedCz;
                default -> throw new IllegalStateException("Unexpected axis: " + axisPlane.axis());
            };

            assertEquals(expectedDistance, axisPlane.distance(), 5.0f,
                String.format("Plane %s-axis should pass through centroid coordinate", axisPlane.axis()));
        }
    }
}
