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
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LevelSelector optimization logic
 *
 * @author hal.hildebrand
 */
public class LevelSelectorTest {

    @Test
    public void testGetAdaptiveSubdivisionThreshold() {
        int baseThreshold = 10;

        // Levels 0-10 should return base threshold
        for (byte level = 0; level <= 10; level++) {
            assertEquals(baseThreshold, LevelSelector.getAdaptiveSubdivisionThreshold(level, baseThreshold),
                         "Levels 0-10 should return base threshold");
        }

        // Level 11 should double
        assertEquals(20, LevelSelector.getAdaptiveSubdivisionThreshold((byte) 11, baseThreshold));

        // Level 12 should quadruple
        assertEquals(40, LevelSelector.getAdaptiveSubdivisionThreshold((byte) 12, baseThreshold));

        // Level 13 should be 8x
        assertEquals(80, LevelSelector.getAdaptiveSubdivisionThreshold((byte) 13, baseThreshold));

        // Should cap at MAX_ENTITIES_PER_NODE (1000)
        assertEquals(1000, LevelSelector.getAdaptiveSubdivisionThreshold((byte) 20, baseThreshold));
    }

    @Test
    public void testOptimalLevelConsistency() {
        // Test that similar distributions give similar levels
        List<Point3f> positions1 = new ArrayList<>();
        List<Point3f> positions2 = new ArrayList<>();
        Random rand1 = new Random(42);
        Random rand2 = new Random(42);

        for (int i = 0; i < 5000; i++) {
            positions1.add(new Point3f(rand1.nextFloat() * 500, rand1.nextFloat() * 500, rand1.nextFloat() * 500));
            positions2.add(new Point3f(rand2.nextFloat() * 500, rand2.nextFloat() * 500, rand2.nextFloat() * 500));
        }

        byte level1 = LevelSelector.selectOptimalLevel(positions1, 100);
        byte level2 = LevelSelector.selectOptimalLevel(positions2, 100);
        assertEquals(level1, level2, "Similar distributions should give same level");
    }

    @Test
    public void testSelectOptimalLevelAllSamePoint() {
        List<Point3f> positions = new ArrayList<>();
        Point3f samePoint = new Point3f(100, 100, 100);
        for (int i = 0; i < 1000; i++) {
            positions.add(samePoint);
        }
        byte level = LevelSelector.selectOptimalLevel(positions, 100);
        assertEquals(15, level, "All points at same location should return deep level");
    }

    @Test
    public void testSelectOptimalLevelEmptyList() {
        List<Point3f> positions = new ArrayList<>();
        byte level = LevelSelector.selectOptimalLevel(positions, 100);
        assertEquals(10, level, "Empty list should return default middle level");
    }

    @Test
    public void testSelectOptimalLevelMediumDistribution() {
        List<Point3f> positions = new ArrayList<>();
        Random rand = new Random(42);
        // Medium distribution across 1000x1000x1000 space
        for (int i = 0; i < 5000; i++) {
            positions.add(new Point3f(rand.nextFloat() * 1000, rand.nextFloat() * 1000, rand.nextFloat() * 1000));
        }
        byte level = LevelSelector.selectOptimalLevel(positions, 100);
        assertTrue(level >= 5 && level <= 15, "Level should be in valid range");
        // For 5000 entities with target 100 per node, we need ~50 cells
        // log8(50) ≈ 1.9, so level should be around 2-3, adjusted by spatial spread to 6-7
        assertTrue(level >= 5 && level <= 8, "Medium distribution should suggest level 5-8");
    }

    @Test
    public void testSelectOptimalLevelSinglePoint() {
        List<Point3f> positions = List.of(new Point3f(0, 0, 0));
        byte level = LevelSelector.selectOptimalLevel(positions, 100);
        assertEquals(15, level, "Single point should return deep level");
    }

    @Test
    public void testSelectOptimalLevelSmallCluster() {
        List<Point3f> positions = new ArrayList<>();
        Random rand = new Random(42);
        // Small cluster within 10x10x10 cube
        for (int i = 0; i < 100; i++) {
            positions.add(new Point3f(rand.nextFloat() * 10, rand.nextFloat() * 10, rand.nextFloat() * 10));
        }
        byte level = LevelSelector.selectOptimalLevel(positions, 100);
        assertTrue(level >= 5 && level <= 15, "Level should be in valid range");
        // For 100 entities with target 100 per node, we need only 1 cell, so level should be low
        assertTrue(level <= 10, "Small dataset should suggest lower level for efficiency");
    }

    @Test
    public void testSelectOptimalLevelWideDistribution() {
        List<Point3f> positions = new ArrayList<>();
        Random rand = new Random(42);
        // Wide distribution across 100000x100000x100000 space
        for (int i = 0; i < 10000; i++) {
            positions.add(new Point3f(rand.nextFloat() * 100000, rand.nextFloat() * 100000, rand.nextFloat() * 100000));
        }
        byte level = LevelSelector.selectOptimalLevel(positions, 100);
        assertTrue(level >= 5 && level <= 15, "Level should be in valid range");
        assertTrue(level <= 10, "Wide distribution should suggest coarser level");
    }

    @Test
    public void testShouldUseMortonSortClusteredData() {
        List<Point3f> positions = new ArrayList<>();
        Random rand = new Random(42);
        // Create clustered data - multiple tight clusters
        for (int cluster = 0; cluster < 10; cluster++) {
            float centerX = cluster * 100;
            float centerY = cluster * 100;
            float centerZ = cluster * 100;
            for (int i = 0; i < 200; i++) {
                positions.add(new Point3f(centerX + rand.nextFloat() * 10, centerY + rand.nextFloat() * 10,
                                          centerZ + rand.nextFloat() * 10));
            }
        }
        assertTrue(LevelSelector.shouldUseMortonSort(positions, (byte) 8),
                   "Clustered data at reasonable level should use Morton sorting");
    }

    @Test
    public void testShouldUseMortonSortDeepLevel() {
        List<Point3f> positions = new ArrayList<>();
        Random rand = new Random(42);
        for (int i = 0; i < 10000; i++) {
            positions.add(new Point3f(rand.nextFloat() * 100, rand.nextFloat() * 100, rand.nextFloat() * 100));
        }
        assertFalse(LevelSelector.shouldUseMortonSort(positions, (byte) 15),
                    "Deep levels should not use Morton sorting");
    }

    @Test
    public void testShouldUseMortonSortSmallDataset() {
        List<Point3f> positions = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            positions.add(new Point3f(i, i, i));
        }
        assertFalse(LevelSelector.shouldUseMortonSort(positions, (byte) 10),
                    "Small datasets should not use Morton sorting");
    }

    @Test
    public void testShouldUseMortonSortSparseData() {
        List<Point3f> positions = new ArrayList<>();
        Random rand = new Random(42);
        // Create very sparse data
        for (int i = 0; i < 2000; i++) {
            positions.add(new Point3f(rand.nextFloat() * 10000, rand.nextFloat() * 10000, rand.nextFloat() * 10000));
        }
        assertFalse(LevelSelector.shouldUseMortonSort(positions, (byte) 8),
                    "Sparse data should not benefit from Morton sorting");
    }
}
