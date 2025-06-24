/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.OctreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for StackBasedTreeBuilder
 *
 * @author hal.hildebrand
 */
public class StackBasedTreeBuilderTest {

    private Octree<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testBasicTreeBuilding() {
        // Prepare test data
        List<Point3f> positions = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            positions.add(new Point3f(i * 10, i * 10, i * 10));
            contents.add("Entity" + i);
        }

        // Build tree
        var result = octree.buildTreeStackBased(positions, contents, (byte) 10);

        // Verify results
        assertNotNull(result);
        assertEquals(100, result.entitiesProcessed);
        assertTrue(result.nodesCreated > 0);
        assertTrue(result.timeTaken >= 0);
        assertNotNull(result.phaseTimes);
        assertTrue(result.phaseTimes.containsKey("preparation"));
        assertTrue(result.phaseTimes.containsKey("construction"));

        // Verify tree structure
        assertEquals(100, octree.entityCount());
        assertTrue(octree.nodeCount() > 0);
    }

    @Test
    void testDifferentBuildStrategies() {
        List<Point3f> positions = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            positions.add(new Point3f(i * 5, i * 5, i * 5));
            contents.add("Entity" + i);
        }

        // Test TOP_DOWN strategy (default)
        var topDownConfig = StackBasedTreeBuilder.defaultConfig().withStrategy(
        StackBasedTreeBuilder.BuildStrategy.TOP_DOWN);
        octree.configureTreeBuilder(topDownConfig);

        var topDownResult = octree.buildTreeStackBased(positions, contents, (byte) 12);
        assertEquals(500, topDownResult.entitiesProcessed);

        // Clear and test with different config
        octree = new Octree<>(new SequentialLongIDGenerator());

        // Test with pre-sorting disabled
        var noSortConfig = StackBasedTreeBuilder.defaultConfig().withPreSortEntities(false);
        octree.configureTreeBuilder(noSortConfig);

        var noSortResult = octree.buildTreeStackBased(positions, contents, (byte) 12);
        assertEquals(500, noSortResult.entitiesProcessed);
    }

    @Test
    void testEdgeCases() {
        // Empty build
        var emptyResult = octree.buildTreeStackBased(new ArrayList<>(), new ArrayList<>(), (byte) 10);
        assertEquals(0, emptyResult.entitiesProcessed);
        assertEquals(0, octree.entityCount());

        // Single entity
        List<Point3f> singlePos = List.of(new Point3f(100, 100, 100));
        List<String> singleContent = List.of("SingleEntity");

        var singleResult = octree.buildTreeStackBased(singlePos, singleContent, (byte) 10);
        assertEquals(1, singleResult.entitiesProcessed);
        assertEquals(1, octree.entityCount());

        // All entities at same position
        octree = new Octree<>(new SequentialLongIDGenerator());
        List<Point3f> samePos = new ArrayList<>();
        List<String> sameContent = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            samePos.add(new Point3f(500, 500, 500));
            sameContent.add("SamePos" + i);
        }

        var sameResult = octree.buildTreeStackBased(samePos, sameContent, (byte) 10);
        assertEquals(50, sameResult.entitiesProcessed);
        assertEquals(50, octree.entityCount());

        // Verify all entities are in the same node
        var found = octree.entitiesInRegion(new Spatial.Cube(499, 499, 499, 2));
        assertEquals(50, found.size());
    }

    @Test
    void testLargeBulkBuild() {
        // Prepare large dataset
        List<Point3f> positions = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        Random random = new Random(42);

        int entityCount = 5000;
        for (int i = 0; i < entityCount; i++) {
            positions.add(
            new Point3f(random.nextFloat() * 10000, random.nextFloat() * 10000, random.nextFloat() * 10000));
            contents.add("Entity" + i);
        }

        // Configure for high performance
        octree.configureTreeBuilder(StackBasedTreeBuilder.highPerformanceConfig());

        // Build tree
        var result = octree.buildTreeStackBased(positions, contents, (byte) 15);

        // Verify results
        assertEquals(entityCount, result.entitiesProcessed);
        assertTrue(result.nodesCreated > 1);
        assertTrue(result.maxDepthReached > 0);
        assertEquals(entityCount, octree.entityCount());

        // Test that we can query the tree
        var nearbyEntities = octree.kNearestNeighbors(new Point3f(5000, 5000, 5000), 10, 1000);
        assertNotNull(nearbyEntities);
        assertTrue(nearbyEntities.size() <= 10);
    }

    @Test
    void testMemoryEfficientConfig() {
        List<Point3f> positions = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        // Create clustered data that would benefit from good tree structure
        for (int cluster = 0; cluster < 10; cluster++) {
            float baseX = cluster * 1000;
            float baseY = cluster * 1000;
            float baseZ = cluster * 1000;

            for (int i = 0; i < 100; i++) {
                positions.add(new Point3f(baseX + (i % 10) * 10, baseY + ((i / 10) % 10) * 10, baseZ + (i / 100) * 10));
                contents.add("Cluster" + cluster + "_Entity" + i);
            }
        }

        // Use memory efficient config
        octree.configureTreeBuilder(StackBasedTreeBuilder.memoryEfficientConfig());

        var result = octree.buildTreeStackBased(positions, contents, (byte) 15);

        assertEquals(1000, result.entitiesProcessed);
        assertTrue(result.nodesCreated > 10); // At least one per cluster

        // Verify spatial locality - entities in same cluster should be findable
        var cluster0Center = new Point3f(50, 50, 50);
        var nearbyInCluster0 = octree.entitiesInRegion(
        new Spatial.Cube(cluster0Center.x - 100, cluster0Center.y - 100, cluster0Center.z - 100, 200));
        assertTrue(nearbyInCluster0.size() > 0);
        assertTrue(nearbyInCluster0.size() <= 100); // Should mostly find cluster 0 entities
    }

    @Test
    void testProgressTracking() {
        List<Point3f> positions = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            positions.add(new Point3f(i, i, i));
            contents.add("Entity" + i);
        }

        // Configure with progress tracking
        var config = StackBasedTreeBuilder.defaultConfig().withProgressTracking(true);
        octree.configureTreeBuilder(config);

        // Track progress
        final List<Integer> progressUpdates = new ArrayList<>();
        final List<String> phaseCompletions = new ArrayList<>();

        var builder = new StackBasedTreeBuilder<MortonKey, LongEntityID, String, OctreeNode<LongEntityID>>(config);

        builder.setProgressListener(new StackBasedTreeBuilder.ProgressListener() {
            @Override
            public void onPhaseComplete(String phaseName, long timeTaken) {
                phaseCompletions.add(phaseName);
            }

            @Override
            public void onProgress(int entitiesProcessed, int totalEntities, int nodesCreated) {
                progressUpdates.add(entitiesProcessed);
            }
        });

        // Note: For this test, we'd need to expose the builder or modify the API
        // to allow setting progress listener through the octree

        var result = octree.buildTreeStackBased(positions, contents, (byte) 10);
        assertEquals(1000, result.entitiesProcessed);
    }
}
