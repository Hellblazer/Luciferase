/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParallelViolationDetector - parallel 2:1 balance violation detection.
 *
 * @author hal.hildebrand
 */
class ParallelViolationDetectorTest {

    private ParallelViolationDetector<MortonKey, LongEntityID, String> detector;
    private TwoOneBalanceChecker<MortonKey, LongEntityID, String> checker;
    private Forest<MortonKey, LongEntityID, String> forest;
    private GhostLayer<MortonKey, LongEntityID, String> ghostLayer;

    @BeforeEach
    void setUp() {
        // Create checker
        checker = new TwoOneBalanceChecker<>();

        // Create forest with one tree
        forest = new Forest<>(ForestConfig.defaultConfig());
        var idGen = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGen);
        forest.addTree(octree);

        // Create ghost layer
        ghostLayer = new GhostLayer<>(GhostType.FACES);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (detector != null) {
            detector.close();
        }
    }

    @Test
    void testConstructionWithDefaultParallelism() {
        detector = new ParallelViolationDetector<>(checker);

        assertNotNull(detector);
        // Verify default parallelism is set (should be number of processors)
        assertTrue(detector.getParallelism() > 0);
        assertEquals(Runtime.getRuntime().availableProcessors(), detector.getParallelism());
    }

    @Test
    void testConstructionWithCustomParallelism() {
        int customParallelism = 4;
        detector = new ParallelViolationDetector<>(checker, customParallelism);

        assertNotNull(detector);
        assertEquals(customParallelism, detector.getParallelism());
    }

    @Test
    void testDetectionWithEmptyGhostLayer() {
        detector = new ParallelViolationDetector<>(checker);

        var violations = detector.detectViolations(ghostLayer, forest);

        assertNotNull(violations);
        assertTrue(violations.isEmpty());
    }

    @Test
    void testDetectionWithNoViolations() {
        detector = new ParallelViolationDetector<>(checker);

        // Add entities at same level (no violations)
        var trees = forest.getAllTrees();
        var tree = trees.get(0);
        var spatialIndex = (Octree<LongEntityID, String>) tree.getSpatialIndex();

        // Add entities at level 5
        spatialIndex.insert(new LongEntityID(1L), new Point3f(0, 0, 0), (byte) 5, "entity1");
        spatialIndex.insert(new LongEntityID(2L), new Point3f(1, 0, 0), (byte) 5, "entity2");

        // Ghost layer is empty - no violations possible
        var violations = detector.detectViolations(ghostLayer, forest);

        assertNotNull(violations);
        assertTrue(violations.isEmpty());
    }

    @Test
    void testProperShutdown() throws Exception {
        detector = new ParallelViolationDetector<>(checker);

        // Use detector
        detector.detectViolations(ghostLayer, forest);

        // Close should succeed without exception
        assertDoesNotThrow(() -> detector.close());

        // Verify detector is properly closed (executor shutdown)
        assertTrue(detector.isShutdown());
    }

    @Test
    void testDetectionWithMultipleThreads() {
        // Use small parallelism for predictable testing
        detector = new ParallelViolationDetector<>(checker, 2);

        // Empty ghost layer - should still work with multiple threads
        var violations = detector.detectViolations(ghostLayer, forest);

        assertNotNull(violations);
        assertTrue(violations.isEmpty());
    }

    @Test
    void testNullCheckerThrowsException() {
        assertThrows(NullPointerException.class, () -> {
            new ParallelViolationDetector<MortonKey, LongEntityID, String>(null);
        });
    }

    @Test
    void testInvalidParallelismThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ParallelViolationDetector<>(checker, 0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ParallelViolationDetector<>(checker, -1);
        });
    }

    @Test
    void testDetectionWithNullGhostLayerThrowsException() {
        detector = new ParallelViolationDetector<>(checker);

        assertThrows(IllegalArgumentException.class, () -> {
            detector.detectViolations(null, forest);
        });
    }

    @Test
    void testDetectionWithNullForestThrowsException() {
        detector = new ParallelViolationDetector<>(checker);

        assertThrows(IllegalArgumentException.class, () -> {
            detector.detectViolations(ghostLayer, null);
        });
    }
}
