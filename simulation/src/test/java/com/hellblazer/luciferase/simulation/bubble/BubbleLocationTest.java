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

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Test BubbleLocation record functionality.
 *
 * @author hal.hildebrand
 */
class BubbleLocationTest {

    @Test
    void testCreation() {
        var key = TetreeKey.create((byte) 5, 42L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(key);

        var location = new BubbleLocation(key, bounds);

        assertThat(location.key()).isEqualTo(key);
        assertThat(location.bounds()).isEqualTo(bounds);
    }

    @Test
    void testContainsPosition() {
        var key = TetreeKey.create((byte) 10, 0L, 0L);
        var positions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f),
            new Point3f(150f, 150f, 150f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);
        var location = new BubbleLocation(key, bounds);

        // Position inside bounds
        assertThat(location.contains(new Point3f(150f, 150f, 150f))).isTrue();

        // Position at boundary
        assertThat(location.contains(new Point3f(100f, 100f, 100f))).isTrue();

        // Position outside bounds
        assertThat(location.contains(new Point3f(500f, 500f, 500f))).isFalse();
    }

    @Test
    void testOverlapsOtherBounds() {
        var key1 = TetreeKey.create((byte) 10, 0L, 0L);
        var positions1 = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f)
        );
        var bounds1 = BubbleBounds.fromEntityPositions(positions1);
        var location1 = new BubbleLocation(key1, bounds1);

        // Create overlapping location
        var key2 = TetreeKey.create((byte) 10, 1L, 0L);
        var positions2 = List.of(
            new Point3f(150f, 150f, 150f),
            new Point3f(250f, 250f, 250f)
        );
        var bounds2 = BubbleBounds.fromEntityPositions(positions2);
        var location2 = new BubbleLocation(key2, bounds2);

        // Should overlap
        assertThat(location1.overlaps(location2)).isTrue();
        assertThat(location2.overlaps(location1)).isTrue();

        // Create non-overlapping location
        var key3 = TetreeKey.create((byte) 10, 100L, 0L);
        var positions3 = List.of(
            new Point3f(1000f, 1000f, 1000f),
            new Point3f(1100f, 1100f, 1100f)
        );
        var bounds3 = BubbleBounds.fromEntityPositions(positions3);
        var location3 = new BubbleLocation(key3, bounds3);

        // Should not overlap
        assertThat(location1.overlaps(location3)).isFalse();
        assertThat(location3.overlaps(location1)).isFalse();
    }

    @Test
    void testUpdateBoundsAddsEntities() {
        var key = TetreeKey.create((byte) 10, 0L, 0L);
        var initialPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f)
        );
        var initialBounds = BubbleBounds.fromEntityPositions(initialPositions);
        var location = new BubbleLocation(key, initialBounds);

        // Add new entities that expand bounds
        var newPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f),
            new Point3f(300f, 300f, 300f)  // New position expands bounds
        );

        var updatedLocation = location.updateBounds(newPositions);

        assertThat(updatedLocation).isNotSameAs(location);  // New instance
        assertThat(updatedLocation.contains(new Point3f(300f, 300f, 300f))).isTrue();
    }

    @Test
    void testUpdateBoundsRemovesEntities() {
        var key = TetreeKey.create((byte) 10, 0L, 0L);
        var initialPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f),
            new Point3f(300f, 300f, 300f)
        );
        var initialBounds = BubbleBounds.fromEntityPositions(initialPositions);
        var location = new BubbleLocation(key, initialBounds);

        // Remove entity (bounds should potentially shrink)
        var newPositions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f)
        );

        var updatedLocation = location.updateBounds(newPositions);

        assertThat(updatedLocation).isNotSameAs(location);  // New instance
        // Bounds recalculated from remaining positions
        assertThat(updatedLocation.key().getLevel()).isEqualTo(key.getLevel());
    }

    @Test
    void testIsAtRoot() {
        // Root level
        var rootKey = TetreeKey.create((byte) 0, 0L, 0L);
        var rootBounds = BubbleBounds.fromTetreeKey(rootKey);
        var rootLocation = new BubbleLocation(rootKey, rootBounds);

        assertThat(rootLocation.isAtRoot()).isTrue();

        // Non-root level
        var nonRootKey = TetreeKey.create((byte) 5, 42L, 0L);
        var nonRootBounds = BubbleBounds.fromTetreeKey(nonRootKey);
        var nonRootLocation = new BubbleLocation(nonRootKey, nonRootBounds);

        assertThat(nonRootLocation.isAtRoot()).isFalse();
    }

    @Test
    void testBoundsExpansion() {
        var key = TetreeKey.create((byte) 10, 0L, 0L);
        var positions = List.of(
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);
        var location = new BubbleLocation(key, bounds);

        // Update with expanded positions
        var expandedPositions = List.of(
            new Point3f(50f, 50f, 50f),   // Expands min
            new Point3f(100f, 100f, 100f),
            new Point3f(200f, 200f, 200f),
            new Point3f(250f, 250f, 250f) // Expands max
        );

        var expandedLocation = location.updateBounds(expandedPositions);

        // Verify expanded bounds contain all positions
        assertThat(expandedLocation.contains(new Point3f(50f, 50f, 50f))).isTrue();
        assertThat(expandedLocation.contains(new Point3f(250f, 250f, 250f))).isTrue();
    }

    @Test
    void testEqualsAndHashCode() {
        var key = TetreeKey.create((byte) 10, 42L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(key);

        var location1 = new BubbleLocation(key, bounds);
        var location2 = new BubbleLocation(key, bounds);

        // Record equality
        assertThat(location1).isEqualTo(location2);
        assertThat(location1.hashCode()).isEqualTo(location2.hashCode());

        // Different key
        var differentKey = TetreeKey.create((byte) 10, 43L, 0L);
        var location3 = new BubbleLocation(differentKey, bounds);

        assertThat(location1).isNotEqualTo(location3);
    }
}
