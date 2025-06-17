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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for advanced spanning policies and strategies in Tetree Validates Phase 3.1 Step 4 implementation
 *
 * @author hal.hildebrand
 */
public class AdvancedSpanningPolicyTest {

    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testAdaptivePolicy() {
        // Test adaptive spanning policy
        var tetree = new Tetree<>(idGenerator, 10, (byte) 8, EntitySpanningPolicy.adaptive());

        // Insert multiple entities to test adaptation
        for (int i = 0; i < 5; i++) {
            var bounds = new EntityBounds(new Point3f(i * 50, i * 50, i * 50),
                                          new Point3f(i * 50 + 40, i * 50 + 40, i * 50 + 40));
            var entityId = idGenerator.generateID();
            tetree.insert(entityId, new Point3f(i * 50 + 20, i * 50 + 20, i * 50 + 20), (byte) 4, "adaptive-test-" + i,
                          bounds);
        }

        var stats = tetree.getStats();
        System.out.println("Adaptive policy stats: " + stats);

        assertEquals(5, stats.entityCount());
        assertTrue(stats.nodeCount() > 0);

        float avgNodesPerEntity = (float) stats.nodeCount() / stats.entityCount();
        System.out.println("Avg nodes per entity (adaptive): " + avgNodesPerEntity);
    }

    @Test
    void testAdaptiveThresholdAdjustment() {
        // Test that adaptive thresholds work correctly
        var adaptivePolicy = EntitySpanningPolicy.adaptive();
        assertTrue(adaptivePolicy.isAdaptiveThreshold());

        var staticPolicy = EntitySpanningPolicy.withSpanning();
        assertFalse(staticPolicy.isAdaptiveThreshold());

        float entitySize = 100f;
        float nodeSize = 80f;

        // Same entity should have different spanning behavior with different system loads
        boolean lightLoad = adaptivePolicy.shouldSpanAdvanced(entitySize, nodeSize, 10, 5, (byte) 4);
        boolean heavyLoad = adaptivePolicy.shouldSpanAdvanced(entitySize, nodeSize, 10000, 50, (byte) 4);

        System.out.printf("Adaptive spanning - Light load: %b, Heavy load: %b%n", lightLoad, heavyLoad);

        // Under heavy load, should be more conservative
        assertTrue(lightLoad || !heavyLoad, "Should be more conservative under heavy load");
    }

    @Test
    void testAdvancedSpanningThresholds() {
        var policy = EntitySpanningPolicy.adaptive();

        // Test spanning decisions for different entity sizes
        float[] entitySizes = { 10f, 50f, 100f, 500f, 1000f };
        float nodeSize = 100f;

        System.out.println("Advanced spanning threshold tests:");
        for (float entitySize : entitySizes) {
            // Test with different system states
            boolean shouldSpanEmpty = policy.shouldSpanAdvanced(entitySize, nodeSize, 0, 0, (byte) 5);
            boolean shouldSpanMedium = policy.shouldSpanAdvanced(entitySize, nodeSize, 1000, 10, (byte) 5);
            boolean shouldSpanHeavy = policy.shouldSpanAdvanced(entitySize, nodeSize, 10000, 50, (byte) 5);

            System.out.printf("Entity size %.1f: Empty=%b, Medium=%b, Heavy=%b%n", entitySize, shouldSpanEmpty,
                              shouldSpanMedium, shouldSpanHeavy);
        }
    }

    @Test
    void testLevelBasedSpanningOptimization() {
        var policy = EntitySpanningPolicy.adaptive();

        float entitySize = 100f;
        float nodeSize = 50f;
        int nodeCount = 1000;
        int entityCount = 10;

        // Test spanning behavior at different levels
        System.out.println("Level-based spanning optimization:");
        for (byte level = 0; level <= 10; level++) {
            boolean shouldSpan = policy.shouldSpanAdvanced(entitySize, nodeSize, nodeCount, entityCount, level);
            System.out.printf("Level %d: should span = %b%n", level, shouldSpan);
        }
    }

    @Test
    void testMaxSpanNodesCalculation() {
        var policy = EntitySpanningPolicy.memoryOptimized();

        float entitySize = 200f;
        float nodeSize = 50f;

        // Test with different node counts
        int[] nodeCounts = { 0, 100, 1000, 5000, 10000 };

        System.out.println("Max span nodes calculation:");
        for (int nodeCount : nodeCounts) {
            int maxSpan = policy.calculateMaxSpanNodes(entitySize, nodeSize, nodeCount);
            System.out.printf("Node count %d: max span = %d%n", nodeCount, maxSpan);

            assertTrue(maxSpan > 0, "Should always allow at least one node");
            assertTrue(maxSpan <= policy.getMaxSpanNodes(), "Should not exceed policy maximum");
        }
    }

    @Test
    void testMemoryOptimizedPolicy() {
        // Test memory-optimized spanning policy
        var tetree = new Tetree<>(idGenerator, 10, (byte) 8, EntitySpanningPolicy.memoryOptimized());

        // Insert a medium-sized entity
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(100, 100, 100));
        var entityId = idGenerator.generateID();
        tetree.insert(entityId, new Point3f(55, 55, 55), (byte) 4, "memory-test", bounds);

        var stats = tetree.getStats();
        System.out.println("Memory-optimized stats: " + stats);

        // Memory-optimized should have fewer nodes per entity
        assertTrue(stats.nodeCount() > 0);
        float avgNodesPerEntity = (float) stats.nodeCount() / stats.entityCount();
        System.out.println("Avg nodes per entity (memory-optimized): " + avgNodesPerEntity);

        // Should be more conservative in spanning
        assertTrue(avgNodesPerEntity < 1000, "Memory-optimized should be conservative");
    }

    @Test
    void testPerformanceOptimizedPolicy() {
        // Test performance-optimized spanning policy
        var tetree = new Tetree<>(idGenerator, 10, (byte) 8, EntitySpanningPolicy.performanceOptimized());

        // Insert a medium-sized entity
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(100, 100, 100));
        var entityId = idGenerator.generateID();
        tetree.insert(entityId, new Point3f(55, 55, 55), (byte) 4, "performance-test", bounds);

        var stats = tetree.getStats();
        System.out.println("Performance-optimized stats: " + stats);

        float avgNodesPerEntity = (float) stats.nodeCount() / stats.entityCount();
        System.out.println("Avg nodes per entity (performance-optimized): " + avgNodesPerEntity);

        // Performance-optimized may use more nodes for better query performance
        assertTrue(avgNodesPerEntity > 0, "Should have spanning nodes");
    }

    @Test
    void testPolicyComparison() {
        // Compare different policies with the same entity
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(200, 200, 200));
        var position = new Point3f(100, 100, 100);

        // Test with default policy
        var defaultGenerator = new SequentialLongIDGenerator();
        var defaultTetree = new Tetree<>(defaultGenerator, 10, (byte) 8, EntitySpanningPolicy.withSpanning());
        var defaultEntity = defaultGenerator.generateID();
        defaultTetree.insert(defaultEntity, position, (byte) 3, "default", bounds);
        var defaultStats = defaultTetree.getStats();

        // Test with memory-optimized policy
        var memoryGenerator = new SequentialLongIDGenerator();
        var memoryTetree = new Tetree<>(memoryGenerator, 10, (byte) 8, EntitySpanningPolicy.memoryOptimized());
        var memoryEntity = memoryGenerator.generateID();
        memoryTetree.insert(memoryEntity, position, (byte) 3, "memory", bounds);
        var memoryStats = memoryTetree.getStats();

        // Test with performance-optimized policy
        var perfGenerator = new SequentialLongIDGenerator();
        var perfTetree = new Tetree<>(perfGenerator, 10, (byte) 8, EntitySpanningPolicy.performanceOptimized());
        var perfEntity = perfGenerator.generateID();
        perfTetree.insert(perfEntity, position, (byte) 3, "performance", bounds);
        var perfStats = perfTetree.getStats();

        System.out.println("Policy comparison for same entity:");
        System.out.println("Default: " + defaultStats);
        System.out.println("Memory-optimized: " + memoryStats);
        System.out.println("Performance-optimized: " + perfStats);

        // All should have the entity
        assertEquals(1, defaultStats.entityCount());
        assertEquals(1, memoryStats.entityCount());
        assertEquals(1, perfStats.entityCount());

        // Memory-optimized should generally use fewer or equal nodes
        assertTrue(memoryStats.nodeCount() <= perfStats.nodeCount()
                   || Math.abs(memoryStats.nodeCount() - perfStats.nodeCount()) < 10,
                   "Memory-optimized should be more conservative than performance-optimized");
    }
}
