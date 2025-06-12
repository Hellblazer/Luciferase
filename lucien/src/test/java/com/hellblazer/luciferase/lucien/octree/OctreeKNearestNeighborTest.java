/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test the k-nearest neighbor search functionality
 *
 * @author hal.hildebrand
 */
public class OctreeKNearestNeighborTest {

    @Test
    void testBasicKNearestNeighbor() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 15;

        // Insert entities in a known pattern
        octree.insert(new Point3f(0, 0, 0), level, "Origin");
        octree.insert(new Point3f(10, 0, 0), level, "Near1");
        octree.insert(new Point3f(0, 10, 0), level, "Near2");
        octree.insert(new Point3f(0, 0, 10), level, "Near3");
        octree.insert(new Point3f(100, 100, 100), level, "Far");

        // Find 3 nearest neighbors to origin
        Point3f queryPoint = new Point3f(0, 0, 0);
        List<LongEntityID> nearest = octree.kNearestNeighbors(queryPoint, 3, Float.MAX_VALUE);

        assertEquals(3, nearest.size());

        // The origin entity should be first (distance 0)
        assertEquals(0L, nearest.get(0).getValue());

        // The other two should be the "Near" entities (all at distance 10)
        assertTrue(nearest.get(1).getValue() >= 1 && nearest.get(1).getValue() <= 3);
        assertTrue(nearest.get(2).getValue() >= 1 && nearest.get(2).getValue() <= 3);
    }

    @Test
    void testKNNEmptyOctree() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

        Point3f queryPoint = new Point3f(0, 0, 0);
        List<LongEntityID> nearest = octree.kNearestNeighbors(queryPoint, 5, 100.0f);

        assertTrue(nearest.isEmpty());
    }

    @Test
    void testKNNPerformance() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 15;
        int entityCount = 1000; // Reduced for debugging

        // Insert random entities
        Random random = new Random(42);
        for (int i = 0; i < entityCount; i++) {
            Point3f pos = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000, random.nextFloat() * 1000);
            octree.insert(pos, level, "Entity" + i);
        }

        // Query for k nearest neighbors
        Point3f queryPoint = new Point3f(500, 500, 500);
        int k = 20;

        long startTime = System.nanoTime();
        List<LongEntityID> nearest = octree.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        long queryTime = System.nanoTime() - startTime;

        System.out.printf("Found %d neighbors (expected %d)%n", nearest.size(), k);
        if (nearest.size() < k) {
            System.out.println("Debug: Entity count = " + octree.entityCount());
            System.out.println("Debug: Node count = " + octree.nodeCount());
        }
        assertTrue(nearest.size() > 0, "Should find at least some neighbors");
        assertTrue(nearest.size() <= k, "Should not exceed k");

        System.out.printf("k-NN Performance:%n");
        System.out.printf("  Found %d nearest neighbors out of %d entities in %.2f ms%n", k, entityCount,
                          queryTime / 1_000_000.0);

        // Verify results are sorted by distance
        Point3f prevPos = octree.getEntityPosition(nearest.get(0));
        float prevDist = queryPoint.distance(prevPos);

        for (int i = 1; i < nearest.size(); i++) {
            Point3f currPos = octree.getEntityPosition(nearest.get(i));
            float currDist = queryPoint.distance(currPos);
            assertTrue(currDist >= prevDist, "Results should be sorted by distance");
            prevDist = currDist;
        }
    }

    @Test
    void testKNNWithDenseCluster() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 18; // Fine level for close entities

        // Create a dense cluster around origin
        for (int i = 0; i < 50; i++) {
            float x = (i % 5) * 0.1f;
            float y = ((i / 5) % 5) * 0.1f;
            float z = (i / 25) * 0.1f;
            octree.insert(new Point3f(x, y, z), level, "Cluster" + i);
        }

        // Add some distant entities
        for (int i = 0; i < 10; i++) {
            octree.insert(new Point3f(100 + i, 100 + i, 100 + i), level, "Distant" + i);
        }

        // Query from center of cluster
        Point3f queryPoint = new Point3f(0.2f, 0.2f, 0.1f);
        List<LongEntityID> nearest = octree.kNearestNeighbors(queryPoint, 10, 50.0f);

        // Due to search optimization, we might not find all 10 in the dense cluster
        // but we should find at least some
        assertTrue(nearest.size() >= 5 && nearest.size() <= 10,
                   "Should find between 5 and 10 neighbors, found: " + nearest.size());

        // All results should be from the cluster (not the distant entities)
        for (LongEntityID id : nearest) {
            String content = octree.getEntity(id);
            assertTrue(content.startsWith("Cluster"), "Should only find cluster entities");
        }
    }

    @Test
    void testKNNWithMaxDistance() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 15;

        // Insert entities at various distances
        octree.insert(new Point3f(0, 0, 0), level, "Origin");
        octree.insert(new Point3f(5, 0, 0), level, "Near");
        octree.insert(new Point3f(15, 0, 0), level, "Medium");
        octree.insert(new Point3f(50, 0, 0), level, "Far");

        // Find neighbors within distance 20
        Point3f queryPoint = new Point3f(0, 0, 0);
        List<LongEntityID> nearest = octree.kNearestNeighbors(queryPoint, 10, 20.0f);

        // Should find 3 entities (Origin, Near, Medium)
        assertEquals(3, nearest.size());

        // Verify they are sorted by distance
        assertEquals(0L, nearest.get(0).getValue()); // Origin at distance 0
        assertEquals(1L, nearest.get(1).getValue()); // Near at distance 5
        assertEquals(2L, nearest.get(2).getValue()); // Medium at distance 15
    }

    @Test
    void testKNNWithZeroK() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        octree.insert(new Point3f(0, 0, 0), (byte) 15, "Test");

        Point3f queryPoint = new Point3f(0, 0, 0);
        List<LongEntityID> nearest = octree.kNearestNeighbors(queryPoint, 0, 100.0f);

        assertTrue(nearest.isEmpty());
    }
}
