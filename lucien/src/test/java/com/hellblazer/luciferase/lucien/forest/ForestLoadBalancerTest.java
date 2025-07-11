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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ForestLoadBalancer
 */
public class ForestLoadBalancerTest {
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private ForestLoadBalancer<MortonKey, LongEntityID, String> loadBalancer;
    private SequentialLongIDGenerator idGenerator;
    private Map<Integer, SpatialIndex<MortonKey, LongEntityID, String>> treeIndexMap;
    
    @BeforeEach
    void setUp() {
        var config = ForestConfig.defaultConfig();
        forest = new Forest<>(config);
        idGenerator = new SequentialLongIDGenerator();
        treeIndexMap = new HashMap<>();
        
        // Create trees with different initial loads
        for (int i = 0; i < 4; i++) {
            var tree = new Octree<LongEntityID, String>(idGenerator);
            var metadata = TreeMetadata.builder()
                .name("tree_" + i)
                .treeType(TreeMetadata.TreeType.OCTREE)
                .build();
            var treeId = forest.addTree(tree, metadata);
            // Extract numeric ID from treeId (e.g., "tree_0_0" -> 0)
            var numericId = Integer.parseInt(treeId.split("_")[2]);
            treeIndexMap.put(numericId, tree);
        }
        
        loadBalancer = new ForestLoadBalancer<>();
    }
    
    @Test
    void testAnalyzeLoadDistribution() {
        // Collect initial metrics
        loadBalancer.collectMetrics(treeIndexMap);
        
        // Create uneven load distribution
        var trees = new ArrayList<>(treeIndexMap.values());
        
        // Tree 0: 100 entities
        for (int i = 0; i < 100; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(50, i * 0.5f, 50);
            trees.get(0).insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Tree 1: 50 entities
        for (int i = 100; i < 150; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(150, (i - 100) * 0.5f, 50);
            trees.get(1).insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Tree 2: 150 entities
        for (int i = 150; i < 300; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(250, (i - 150) * 0.5f, 50);
            trees.get(2).insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Tree 3: 0 entities (empty)
        
        // Collect metrics again after adding entities
        loadBalancer.collectMetrics(treeIndexMap);
        
        // Verify metrics
        var metrics0 = loadBalancer.getMetrics(0);
        assertNotNull(metrics0);
        assertEquals(100, metrics0.getEntityCount());
        
        var metrics1 = loadBalancer.getMetrics(1);
        assertNotNull(metrics1);
        assertEquals(50, metrics1.getEntityCount());
        
        var metrics2 = loadBalancer.getMetrics(2);
        assertNotNull(metrics2);
        assertEquals(150, metrics2.getEntityCount());
        
        var metrics3 = loadBalancer.getMetrics(3);
        assertNotNull(metrics3);
        assertEquals(0, metrics3.getEntityCount());
    }
    
    @Test
    void testIdentifyOverloadedTrees() {
        loadBalancer.collectMetrics(treeIndexMap);
        var trees = new ArrayList<>(treeIndexMap.values());
        
        // Create one heavily loaded tree
        for (int i = 0; i < 200; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(50, i * 0.25f, 50);
            trees.get(0).insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Other trees have normal load
        for (int t = 1; t < 4; t++) {
            for (int i = 0; i < 50; i++) {
                var id = new LongEntityID(t * 1000 + i);
                var pos = new Point3f(t * 100 + 50, i, 50);
                trees.get(t).insert(id, pos, (byte)10, "Entity " + id);
            }
        }
        
        // Collect metrics after adding entities
        loadBalancer.collectMetrics(treeIndexMap);
        
        var overloaded = loadBalancer.identifyOverloadedTrees();
        
        assertEquals(1, overloaded.size());
        assertEquals(0, overloaded.get(0).intValue());
    }
    
    @Test
    void testIdentifyUnderloadedTrees() {
        loadBalancer.collectMetrics(treeIndexMap);
        var trees = new ArrayList<>(treeIndexMap.values());
        
        // All trees have normal load except one
        for (int t = 0; t < 3; t++) {
            for (int i = 0; i < 100; i++) {
                var id = new LongEntityID(t * 1000 + i);
                var pos = new Point3f(t * 100 + 50, i * 0.5f, 50);
                trees.get(t).insert(id, pos, (byte)10, "Entity " + i);
            }
        }
        
        // Tree 3 has very few entities
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(3000 + i);
            var pos = new Point3f(350, i * 5, 50);
            trees.get(3).insert(id, pos, (byte)10, "Entity " + id);
        }
        
        // Collect metrics after adding entities
        loadBalancer.collectMetrics(treeIndexMap);
        
        var underloaded = loadBalancer.identifyUnderloadedTrees();
        
        assertEquals(1, underloaded.size());
        assertEquals(3, underloaded.get(0).intValue());
    }
    
    @Test
    void testCreateMigrationPlans() {
        loadBalancer.collectMetrics(treeIndexMap);
        var trees = new ArrayList<>(treeIndexMap.values());
        
        // Create uneven distribution
        // Tree 0: overloaded with 200 entities
        for (int i = 0; i < 200; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(50, i * 0.25f, 50);
            trees.get(0).insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Tree 1: underloaded with 20 entities
        for (int i = 0; i < 20; i++) {
            var id = new LongEntityID(1000 + i);
            var pos = new Point3f(150, i, 50);
            trees.get(1).insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Trees 2 & 3: normal load with 80 entities each
        for (int t = 2; t < 4; t++) {
            for (int i = 0; i < 80; i++) {
                var id = new LongEntityID(t * 1000 + i);
                var pos = new Point3f(t * 100 + 50, i, 50);
                trees.get(t).insert(id, pos, (byte)10, "Entity " + i);
            }
        }
        
        // Collect metrics and create migration plans
        loadBalancer.collectMetrics(treeIndexMap);
        var plans = loadBalancer.createMigrationPlans(treeIndexMap);
        
        assertFalse(plans.isEmpty());
        
        // Verify plan targets overloaded tree as source
        var plan = plans.get(0);
        assertEquals(0, plan.getSourceTreeId());
        assertTrue(plan.getExpectedLoadReduction() > 0);
        assertFalse(plan.getEntityIds().isEmpty());
    }
    
    @Test
    void testExecuteMigration() {
        loadBalancer.collectMetrics(treeIndexMap);
        var trees = new ArrayList<>(treeIndexMap.values());
        var sourceTree = trees.get(0);
        var targetTree = trees.get(1);
        
        // Add entities to source tree
        Map<LongEntityID, Point3f> entityPositions = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(50 + i * 0.1f, 50, 50);
            entityPositions.put(id, pos);
            sourceTree.insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Create manual migration plan
        var entitiesToMigrate = new HashSet<LongEntityID>();
        for (int i = 0; i < 50; i++) {
            entitiesToMigrate.add(new LongEntityID(i));
        }
        
        var plan = new ForestLoadBalancer.MigrationPlan<>(0, 1, entitiesToMigrate, 0.5);
        
        assertEquals(100, sourceTree.entityCount());
        assertEquals(0, targetTree.entityCount());
        
        // Execute migration
        loadBalancer.executeMigration(plan, treeIndexMap, (id, point) -> {
            var pos = entityPositions.get(id);
            if (pos != null) {
                point.set(pos);
            }
        });
        
        // Verify migration results
        assertEquals(50, sourceTree.entityCount());
        assertEquals(50, targetTree.entityCount());
    }
    
    @Test
    void testLoadBalancerConfiguration() {
        var config = new ForestLoadBalancer.LoadBalancerConfig();
        
        // Test default values
        assertEquals(1.5, config.getOverloadThreshold());
        assertEquals(0.5, config.getUnderloadThreshold());
        assertEquals(ForestLoadBalancer.LoadBalancingStrategy.COMPOSITE, config.getStrategy());
        
        // Test setters
        config.setOverloadThreshold(2.0);
        config.setUnderloadThreshold(0.3);
        config.setMinEntitiesForMigration(50);
        config.setMaxMigrationBatchSize(500);
        config.setTargetLoadRatio(0.9);
        config.setStrategy(ForestLoadBalancer.LoadBalancingStrategy.ENTITY_COUNT);
        
        assertEquals(2.0, config.getOverloadThreshold());
        assertEquals(0.3, config.getUnderloadThreshold());
        assertEquals(50, config.getMinEntitiesForMigration());
        assertEquals(500, config.getMaxMigrationBatchSize());
        assertEquals(0.9, config.getTargetLoadRatio());
        assertEquals(ForestLoadBalancer.LoadBalancingStrategy.ENTITY_COUNT, config.getStrategy());
    }
    
    @Test
    void testRecordQuery() throws InterruptedException {
        loadBalancer.collectMetrics(treeIndexMap);
        
        // Record some queries
        for (int i = 0; i < 10; i++) {
            loadBalancer.recordQuery(0, 1_000_000); // 1ms in nanos
            Thread.sleep(10);
        }
        
        var metrics = loadBalancer.getMetrics(0);
        assertNotNull(metrics);
        assertEquals(10, metrics.getQueryCount());
        assertEquals(10_000_000, metrics.getTotalQueryTimeNanos());
        assertEquals(1_000_000, metrics.getAverageQueryTimeNanos(), 0.01);
        assertTrue(metrics.getQueryRate() > 0);
    }
    
    @Test
    void testConcurrentMetricsCollection() throws InterruptedException {
        int numThreads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        // Each thread performs metrics collection and query recording
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        loadBalancer.collectMetrics(treeIndexMap);
                        loadBalancer.recordQuery(threadId % 4, 100_000);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify metrics were recorded
        for (int i = 0; i < 4; i++) {
            var metrics = loadBalancer.getMetrics(i);
            assertNotNull(metrics);
            assertTrue(metrics.getQueryCount() > 0);
        }
    }
    
    @Test
    void testClearMetrics() {
        loadBalancer.collectMetrics(treeIndexMap);
        
        // Record some data
        loadBalancer.recordQuery(0, 1_000_000);
        assertNotNull(loadBalancer.getMetrics(0));
        
        // Clear metrics
        loadBalancer.clearMetrics();
        
        // Verify metrics are cleared
        assertNull(loadBalancer.getMetrics(0));
    }
    
    @Test
    void testLoadBalancingStrategies() {
        loadBalancer.collectMetrics(treeIndexMap);
        var trees = new ArrayList<>(treeIndexMap.values());
        
        // Add entities to create different load characteristics
        for (int i = 0; i < 100; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(50, i, 50);
            trees.get(0).insert(id, pos, (byte)10, "Entity " + i);
        }
        
        // Record queries to tree 1
        for (int i = 0; i < 50; i++) {
            loadBalancer.recordQuery(1, 1_000_000);
        }
        
        loadBalancer.collectMetrics(treeIndexMap);
        
        var config = loadBalancer.getConfig();
        
        // Test entity count strategy
        config.setStrategy(ForestLoadBalancer.LoadBalancingStrategy.ENTITY_COUNT);
        var overloadedByCount = loadBalancer.identifyOverloadedTrees();
        
        // Test query rate strategy
        config.setStrategy(ForestLoadBalancer.LoadBalancingStrategy.QUERY_RATE);
        var overloadedByQuery = loadBalancer.identifyOverloadedTrees();
        
        // Test composite strategy
        config.setStrategy(ForestLoadBalancer.LoadBalancingStrategy.COMPOSITE);
        var overloadedByComposite = loadBalancer.identifyOverloadedTrees();
        
        // Results may vary based on strategy
        assertNotNull(overloadedByCount);
        assertNotNull(overloadedByQuery);
        assertNotNull(overloadedByComposite);
    }
}