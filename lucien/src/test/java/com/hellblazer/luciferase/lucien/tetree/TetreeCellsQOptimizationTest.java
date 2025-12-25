/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the LITMAX/BIGMIN grid-based cells(Q) optimization in Tetree.
 * Verifies that range queries produce efficient grid cell intervals and
 * correctly enumerate tetrahedra.
 *
 * @author hal.hildebrand
 */
public class TetreeCellsQOptimizationTest {

    @Test
    void testCellsQProducesIntervals() {
        byte level = 8;
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, level);

        var cellSize = Constants.lengthAtLevel(level);
        var query = new VolumeBounds(0, 0, 0, cellSize * 3, cellSize * 3, cellSize * 3);

        var intervals = tetree.cellsQ(query, level);

        // Should produce intervals for the 3x3x3 = 27 grid cells
        assertFalse(intervals.isEmpty(), "Should produce intervals");

        // With LITMAX/BIGMIN, intervals should be efficient (fewer than 27)
        assertTrue(intervals.size() <= 27,
            "3x3x3 query should produce ≤27 intervals, got " + intervals.size());
    }

    @Test
    void testCellsQForLargeQuery() {
        byte level = 8;
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, level);

        var cellSize = Constants.lengthAtLevel(level);
        var largeQuery = new VolumeBounds(
            cellSize, cellSize, cellSize,
            cellSize * 6, cellSize * 6, cellSize * 6
        );

        var intervals = tetree.cellsQ(largeQuery, level);

        // LITMAX/BIGMIN should produce fewer intervals than naive approach
        assertTrue(intervals.size() < 125,
            "Large query should produce fewer than 125 intervals, got " + intervals.size());

        // Verify intervals are valid
        for (var interval : intervals) {
            assertEquals(level, interval.level());
            assertTrue(interval.start() <= interval.end());
        }
    }

    @Test
    void testRangeQueryWithOptimization() {
        byte level = 10;
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, level);

        // Insert entities in a grid pattern
        var cellSize = Constants.lengthAtLevel(level);
        int insertCount = 0;
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    var pos = new Point3f(
                        x * cellSize * 2 + cellSize / 2,
                        y * cellSize * 2 + cellSize / 2,
                        z * cellSize * 2 + cellSize / 2
                    );
                    tetree.insert(pos, level, "entity_" + x + "_" + y + "_" + z);
                    insertCount++;
                }
            }
        }

        assertEquals(125, tetree.entityCount());

        // Query that should cover a subset
        var queryRegion = new Spatial.Cube(
            cellSize * 2, cellSize * 2, cellSize * 2,
            cellSize * 6
        );

        var entities = tetree.entitiesInRegion(queryRegion);

        // Should find entities in the query region
        assertFalse(entities.isEmpty(), "Should find entities in region");
    }

    @Test
    void testOptimizationFallbackForSmallIndex() {
        byte level = 8;
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, level);

        // Insert just a few entities (< 100, so linear scan should be used)
        var cellSize = Constants.lengthAtLevel(level);
        for (int i = 0; i < 10; i++) {
            var pos = new Point3f(i * cellSize + cellSize / 2, cellSize / 2, cellSize / 2);
            tetree.insert(pos, level, "entity_" + i);
        }

        assertEquals(10, tetree.entityCount());

        // Query should still work even with linear scan fallback
        var queryRegion = new Spatial.Cube(0, 0, 0, cellSize * 5);
        var entities = tetree.entitiesInRegion(queryRegion);

        assertTrue(entities.size() > 0 && entities.size() <= 5,
            "Should find some entities in query region");
    }

    @Test
    void testGridCellIntervalCellCount() {
        byte level = 8;
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, level);

        var cellSize = Constants.lengthAtLevel(level);
        var query = new VolumeBounds(0, 0, 0, cellSize * 2, cellSize * 2, cellSize * 2);

        var intervals = tetree.cellsQ(query, level);

        // Verify cellCount() works correctly
        for (var interval : intervals) {
            long count = interval.cellCount();
            assertTrue(count > 0, "Cell count should be positive");
            assertEquals(interval.end() - interval.start() + 1, count);
        }
    }

    @Test
    void testCellsQAtDifferentLevels() {
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, (byte) 15);

        // Test at various levels
        for (byte level = 4; level <= 10; level++) {
            var cellSize = Constants.lengthAtLevel(level);
            var query = new VolumeBounds(0, 0, 0, cellSize * 3, cellSize * 3, cellSize * 3);

            var intervals = tetree.cellsQ(query, level);

            assertFalse(intervals.isEmpty(), "Should produce intervals at level " + level);
            for (var interval : intervals) {
                assertEquals(level, interval.level(), "Interval should have correct level");
            }
        }
    }

    @Test
    void testLargeIndexBenefitsFromOptimization() {
        byte level = 10;
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, level);

        // Insert many entities to trigger optimization (>100)
        var cellSize = Constants.lengthAtLevel(level);
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 2; z++) {
                    var pos = new Point3f(
                        x * cellSize + cellSize / 2,
                        y * cellSize + cellSize / 2,
                        z * cellSize + cellSize / 2
                    );
                    tetree.insert(pos, level, "entity_" + x + "_" + y + "_" + z);
                }
            }
        }

        assertTrue(tetree.entityCount() >= 100, "Should have >=100 entities");

        // Small query should use optimization (fewer cells than index size)
        var smallQuery = new Spatial.Cube(0, 0, 0, cellSize * 3);
        var entities = tetree.entitiesInRegion(smallQuery);

        // Query should work and find some entities
        assertFalse(entities.isEmpty(), "Should find entities in small query region");
    }
}
