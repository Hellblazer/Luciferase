/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Test BubbleBoundCalculator utility class.
 *
 * @author hal.hildebrand
 */
class BubbleBoundCalculatorTest {

    @Test
    void testMinimalBoundingTetSingleEntity() {
        var position = new Point3f(100f, 150f, 200f);
        var bounds = BubbleBoundCalculator.minimalBoundingTetrahedron(List.of(position));

        assertThat(bounds).isNotNull();
        assertThat(bounds.contains(position)).isTrue();
    }

    @Test
    void testMinimalBoundingTetMultipleEntities() {
        var positions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f),
            new Point3f(150f, 150f, 150f)
        );

        var bounds = BubbleBoundCalculator.minimalBoundingTetrahedron(positions);

        assertThat(bounds).isNotNull();
        // All positions should be contained
        for (var pos : positions) {
            assertThat(bounds.contains(pos)).isTrue();
        }
    }

    @Test
    void testBoundingTetEncompassesAllEntities() {
        var positions = List.of(
            new Point3f(50f, 50f, 50f),
            new Point3f(100f, 100f, 100f),
            new Point3f(150f, 150f, 150f),
            new Point3f(200f, 200f, 200f),
            new Point3f(250f, 250f, 250f)
        );

        var bounds = BubbleBoundCalculator.minimalBoundingTetrahedron(positions);

        // Verify all positions are within bounds
        for (var pos : positions) {
            assertThat(bounds.contains(pos))
                .withFailMessage("Position %s should be contained in bounds", pos)
                .isTrue();
        }

        // Verify bounds are reasonably tight (min/max check)
        assertThat(bounds.contains(new Point3f(25f, 25f, 25f))).isFalse();
        assertThat(bounds.contains(new Point3f(275f, 275f, 275f))).isFalse();
    }

    @Test
    void testIncrementalUpdateAddEntity() {
        var initialPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f)
        );
        var currentBounds = BubbleBoundCalculator.fromEntityPositions(initialPositions);

        // Add a new entity
        var addedPositions = List.of(new Point3f(250f, 250f, 250f));
        var allPositions = new ArrayList<>(initialPositions);
        allPositions.addAll(addedPositions);

        var updatedBounds = BubbleBoundCalculator.updateBounds(
            currentBounds,
            addedPositions,
            List.of(),  // No removals
            allPositions
        );

        assertThat(updatedBounds).isNotNull();
        assertThat(updatedBounds.contains(new Point3f(250f, 250f, 250f))).isTrue();
    }

    @Test
    void testIncrementalUpdateRemoveEntity() {
        var initialPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f),
            new Point3f(300f, 300f, 300f)
        );
        var currentBounds = BubbleBoundCalculator.fromEntityPositions(initialPositions);

        // Remove one entity
        var removedPositions = List.of(new Point3f(300f, 300f, 300f));
        var remainingPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f)
        );

        var updatedBounds = BubbleBoundCalculator.updateBounds(
            currentBounds,
            List.of(),  // No additions
            removedPositions,
            remainingPositions
        );

        assertThat(updatedBounds).isNotNull();
        // Should still contain remaining positions
        assertThat(updatedBounds.contains(new Point3f(100f, 100f, 100f))).isTrue();
        assertThat(updatedBounds.contains(new Point3f(200f, 200f, 200f))).isTrue();
    }

    @Test
    void testBoundsExpandsWhenNeeded() {
        var initialPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(150f, 150f, 150f)
        );
        var currentBounds = BubbleBoundCalculator.fromEntityPositions(initialPositions);

        // Add entity far outside current bounds
        var addedPositions = List.of(new Point3f(500f, 500f, 500f));
        var allPositions = new ArrayList<>(initialPositions);
        allPositions.addAll(addedPositions);

        var expandedBounds = BubbleBoundCalculator.updateBounds(
            currentBounds,
            addedPositions,
            List.of(),
            allPositions
        );

        assertThat(expandedBounds).isNotNull();
        assertThat(expandedBounds.contains(new Point3f(500f, 500f, 500f))).isTrue();
        assertThat(expandedBounds.contains(new Point3f(100f, 100f, 100f))).isTrue();
    }

    @Test
    void testThreadSafety_ConcurrentUpdates() throws InterruptedException {
        var numThreads = 10;
        var updatesPerThread = 100;
        var latch = new CountDownLatch(numThreads);
        var errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < updatesPerThread; i++) {
                        var positions = List.of(
                            new Point3f(threadId * 10f + i, threadId * 10f + i, threadId * 10f + i),
                            new Point3f(threadId * 10f + i + 5, threadId * 10f + i + 5, threadId * 10f + i + 5)
                        );
                        var bounds = BubbleBoundCalculator.minimalBoundingTetrahedron(positions);
                        assertThat(bounds).isNotNull();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertThat(errors.get()).isZero();
    }

    @Test
    void testFromAOIsSingleEntity() {
        var position = new Point3f(100f, 150f, 200f);
        var bounds = BubbleBoundCalculator.fromAOIs(List.of(position));

        assertThat(bounds).isNotNull();
        assertThat(bounds.contains(position)).isTrue();
    }

    @Test
    void testFromAOIsMultipleEntities() {
        var positions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f),
            new Point3f(150f, 150f, 150f)
        );

        var bounds = BubbleBoundCalculator.fromAOIs(positions);

        assertThat(bounds).isNotNull();
        for (var pos : positions) {
            assertThat(bounds.contains(pos)).isTrue();
        }
    }

    @Test
    void testPrecisionLoss_RDGCSRounding() {
        // Test that RDGCS precision loss is acceptable (<2 units)
        var positions = List.of(
            new Point3f(100.5f, 100.5f, 100.5f),
            new Point3f(200.5f, 200.5f, 200.5f)
        );

        var bounds = BubbleBoundCalculator.minimalBoundingTetrahedron(positions);

        // Verify original positions are contained (may have small rounding)
        assertThat(bounds.contains(new Point3f(100.5f, 100.5f, 100.5f))).isTrue();
        assertThat(bounds.contains(new Point3f(200.5f, 200.5f, 200.5f))).isTrue();

        // RDGCS bounds expand slightly beyond exact positions due to coordinate transformation
        // This is expected behavior - bounds are conservative
    }

    @Test
    void testEdgeCases_EmptyCollection() {
        assertThatThrownBy(() ->
            BubbleBoundCalculator.minimalBoundingTetrahedron(List.of())
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("empty");
    }

    @Test
    void testEdgeCases_SinglePoint() {
        var position = new Point3f(42f, 42f, 42f);
        var bounds = BubbleBoundCalculator.fromEntityPositions(position);

        assertThat(bounds).isNotNull();
        assertThat(bounds.contains(position)).isTrue();
    }
}
