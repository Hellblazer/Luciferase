package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for parallel octree operations Tests both correctness and performance characteristics
 *
 * @author hal.hildebrand
 */
public class ParallelOctreeOperationsTest {

    private final byte                                    testLevel = 15;
    private       Octree<String>                          largeOctree;
    private       Octree<String>                          smallOctree;
    private       ParallelOctreeOperations.ParallelConfig parallelConfig;
    private       ParallelOctreeOperations.ParallelConfig sequentialConfig;

    @BeforeEach
    void setUp() {
        // Create a small octree (below threshold)
        smallOctree = new Octree<>();
        smallOctree.insert(new Point3f(32.0f, 32.0f, 32.0f), testLevel, "Small1");
        smallOctree.insert(new Point3f(96.0f, 96.0f, 96.0f), testLevel, "Small2");
        smallOctree.insert(new Point3f(160.0f, 160.0f, 160.0f), testLevel, "Small3");

        // Create a larger octree (above threshold)
        largeOctree = new Octree<>();
        int gridSize = Constants.lengthAtLevel(testLevel);

        // Insert enough points to exceed parallelism threshold
        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                float x = (i + 0.5f) * gridSize;
                float y = (j + 0.5f) * gridSize;
                float z = 32.0f;
                largeOctree.insert(new Point3f(x, y, z), testLevel, "Large_" + i + "_" + j);
            }
        }

        // Configure parallel execution
        parallelConfig = ParallelOctreeOperations.ParallelConfig.withCustomPool(10, 4);
        sequentialConfig = ParallelOctreeOperations.ParallelConfig.defaultConfig();
    }

    @Test
    void testBatchKNearestNeighbors() {
        List<Point3f> queryPoints = Arrays.asList(new Point3f(100000.0f, 100000.0f, 32.0f),
                                                  new Point3f(300000.0f, 300000.0f, 32.0f),
                                                  new Point3f(500000.0f, 500000.0f, 32.0f),
                                                  new Point3f(700000.0f, 700000.0f, 32.0f));
        int k = 3;

        Map<Point3f, List<KNearestNeighborSearch.KNNCandidate<String>>> batchResults = ParallelOctreeOperations.batchKNearestNeighbors(
        queryPoints, k, largeOctree, parallelConfig);

        assertEquals(queryPoints.size(), batchResults.size());

        // Each query should have k results
        for (Point3f queryPoint : queryPoints) {
            assertTrue(batchResults.containsKey(queryPoint));
            assertEquals(k, batchResults.get(queryPoint).size());

            // Results should be sorted by distance
            List<KNearestNeighborSearch.KNNCandidate<String>> results = batchResults.get(queryPoint);
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).distance <= results.get(i + 1).distance);
            }
        }
    }

    @Test
    void testBoundedByParallelLargeDataset() {
        // Large dataset should use parallel processing
        Spatial.Cube searchVolume = new Spatial.Cube(100.0f, 100.0f, 100.0f, 1000000.0f);

        List<Octree.Hexahedron<String>> sequentialResults = largeOctree.boundedBy(searchVolume).toList();

        List<Octree.Hexahedron<String>> parallelResults = ParallelOctreeOperations.boundedByParallel(searchVolume,
                                                                                                     largeOctree,
                                                                                                     parallelConfig);

        // Most important: sequential and parallel results should match
        assertEquals(sequentialResults.size(), parallelResults.size());

        // Results should contain the same elements
        Set<String> sequentialContents = sequentialResults.stream().map(h -> h.cell()).collect(Collectors.toSet());
        Set<String> parallelContents = parallelResults.stream().map(h -> h.cell()).collect(Collectors.toSet());

        assertEquals(sequentialContents, parallelContents);
    }

    @Test
    void testBoundedByParallelSmallDataset() {
        // Small dataset should use sequential processing
        Spatial.Cube searchVolume = new Spatial.Cube(10.0f, 10.0f, 10.0f, 200.0f);

        List<Octree.Hexahedron<String>> sequentialResults = smallOctree.boundedBy(searchVolume).toList();

        List<Octree.Hexahedron<String>> parallelResults = ParallelOctreeOperations.boundedByParallel(searchVolume,
                                                                                                     smallOctree,
                                                                                                     parallelConfig);

        assertEquals(sequentialResults.size(), parallelResults.size());

        // Results should contain the same elements (order may differ)
        Set<String> sequentialContents = sequentialResults.stream().map(h -> h.cell()).collect(Collectors.toSet());
        Set<String> parallelContents = parallelResults.stream().map(h -> h.cell()).collect(Collectors.toSet());

        assertEquals(sequentialContents, parallelContents);
    }

    @Test
    void testBoundingParallelCorrectness() {
        Spatial.Sphere searchVolume = new Spatial.Sphere(500000.0f, 500000.0f, 32.0f, 100000.0f);

        List<Octree.Hexahedron<String>> sequentialResults = largeOctree.bounding(searchVolume).toList();

        List<Octree.Hexahedron<String>> parallelResults = ParallelOctreeOperations.boundingParallel(searchVolume,
                                                                                                    largeOctree,
                                                                                                    parallelConfig);

        assertEquals(sequentialResults.size(), parallelResults.size());

        // Results should contain the same elements
        Set<String> sequentialContents = sequentialResults.stream().map(h -> h.cell()).collect(Collectors.toSet());
        Set<String> parallelContents = parallelResults.stream().map(h -> h.cell()).collect(Collectors.toSet());

        assertEquals(sequentialContents, parallelContents);
    }

    @Test
    void testEmptyOctreeParallelOperations() {
        Octree<String> emptyOctree = new Octree<>();
        Spatial.Cube searchVolume = new Spatial.Cube(50.0f, 50.0f, 50.0f, 100.0f);

        List<Octree.Hexahedron<String>> results = ParallelOctreeOperations.boundedByParallel(searchVolume, emptyOctree,
                                                                                             parallelConfig);

        assertTrue(results.isEmpty());
    }

    @Test
    void testKNearestNeighborsParallelCorrectness() {
        Point3f queryPoint = new Point3f(500000.0f, 500000.0f, 32.0f);
        int k = 5;

        List<KNearestNeighborSearch.KNNCandidate<String>> sequentialResults = KNearestNeighborSearch.findKNearestNeighbors(
        queryPoint, k, largeOctree);

        List<KNearestNeighborSearch.KNNCandidate<String>> parallelResults = ParallelOctreeOperations.kNearestNeighborsParallel(
        queryPoint, k, largeOctree, parallelConfig);

        assertEquals(sequentialResults.size(), parallelResults.size());
        assertEquals(k, parallelResults.size());

        // Results should be sorted by distance
        for (int i = 0; i < parallelResults.size() - 1; i++) {
            assertTrue(parallelResults.get(i).distance <= parallelResults.get(i + 1).distance);
        }

        // First result should be the same (closest point)
        assertEquals(sequentialResults.get(0).content, parallelResults.get(0).content);
        assertEquals(sequentialResults.get(0).distance, parallelResults.get(0).distance, 0.001f);
    }

    @Test
    void testParallelConfigCreation() {
        ParallelOctreeOperations.ParallelConfig defaultConfig = ParallelOctreeOperations.ParallelConfig.defaultConfig();

        assertEquals(1000, defaultConfig.parallelismThreshold);
        assertNull(defaultConfig.customThreadPool);

        ParallelOctreeOperations.ParallelConfig customConfig = ParallelOctreeOperations.ParallelConfig.withCustomPool(
        500, 8);

        assertEquals(500, customConfig.parallelismThreshold);
        assertNotNull(customConfig.customThreadPool);
        assertEquals(8, customConfig.customThreadPool.getParallelism());
    }

    @Test
    void testParallelOperationsConsistency() {
        // Test that multiple parallel calls return consistent results
        Point3f queryPoint = new Point3f(500000.0f, 500000.0f, 32.0f);
        int k = 3;

        List<KNearestNeighborSearch.KNNCandidate<String>> results1 = ParallelOctreeOperations.kNearestNeighborsParallel(
        queryPoint, k, largeOctree, parallelConfig);

        List<KNearestNeighborSearch.KNNCandidate<String>> results2 = ParallelOctreeOperations.kNearestNeighborsParallel(
        queryPoint, k, largeOctree, parallelConfig);

        assertEquals(results1.size(), results2.size());

        for (int i = 0; i < results1.size(); i++) {
            assertEquals(results1.get(i).content, results2.get(i).content);
            assertEquals(results1.get(i).distance, results2.get(i).distance, 0.001f);
        }
    }

    @Test
    void testParallelOperationsWithCommonPool() {
        // Test with common thread pool (null custom pool)
        ParallelOctreeOperations.ParallelConfig commonPoolConfig = new ParallelOctreeOperations.ParallelConfig(5, null);

        Point3f queryPoint = new Point3f(500000.0f, 500000.0f, 32.0f);

        List<KNearestNeighborSearch.KNNCandidate<String>> results = ParallelOctreeOperations.kNearestNeighborsParallel(
        queryPoint, 3, largeOctree, commonPoolConfig);

        assertEquals(3, results.size());
        assertNull(commonPoolConfig.customThreadPool);
    }

    @Test
    void testParallelOperationsWithCustomThreadPool() {
        // Test with custom thread pool
        ParallelOctreeOperations.ParallelConfig customConfig = ParallelOctreeOperations.ParallelConfig.withCustomPool(5,
                                                                                                                      2);

        Point3f queryPoint = new Point3f(500000.0f, 500000.0f, 32.0f);

        List<KNearestNeighborSearch.KNNCandidate<String>> results = ParallelOctreeOperations.kNearestNeighborsParallel(
        queryPoint, 3, largeOctree, customConfig);

        assertEquals(3, results.size());

        // Verify custom thread pool properties
        assertEquals(2, customConfig.customThreadPool.getParallelism());
        assertEquals(5, customConfig.parallelismThreshold);

        // Clean up custom thread pool
        customConfig.customThreadPool.shutdown();
    }

    @Test
    void testRayIntersectedAllParallelCorrectness() {
        Point3f origin = new Point3f(1.0f, 500000.0f, 32.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);

        List<RayTracingSearch.RayIntersection<String>> sequentialResults = RayTracingSearch.rayIntersectedAll(ray,
                                                                                                              largeOctree);

        List<RayTracingSearch.RayIntersection<String>> parallelResults = ParallelOctreeOperations.rayIntersectedAllParallel(
        ray, largeOctree, parallelConfig);

        assertEquals(sequentialResults.size(), parallelResults.size());

        // Results should be sorted by distance
        for (int i = 0; i < parallelResults.size() - 1; i++) {
            assertTrue(parallelResults.get(i).distance <= parallelResults.get(i + 1).distance);
        }

        // First intersection should be the same
        if (!sequentialResults.isEmpty()) {
            assertEquals(sequentialResults.get(0).content, parallelResults.get(0).content);
            assertEquals(sequentialResults.get(0).distance, parallelResults.get(0).distance, 0.001f);
        }
    }

    @Test
    void testSmallBatchUsesSequentialProcessing() {
        // Small batch should use sequential processing
        List<Point3f> smallBatch = List.of(new Point3f(100000.0f, 100000.0f, 32.0f));
        int k = 2;

        Map<Point3f, List<KNearestNeighborSearch.KNNCandidate<String>>> batchResults = ParallelOctreeOperations.batchKNearestNeighbors(
        smallBatch, k, smallOctree, parallelConfig);

        assertEquals(1, batchResults.size());
        assertTrue(batchResults.containsKey(smallBatch.get(0)));
    }

    @Test
    void testThresholdBehavior() {
        // Test that operations use sequential processing below threshold
        // and parallel processing above threshold

        // Small octree (below threshold) - should use sequential
        Point3f queryPoint = new Point3f(32.0f, 32.0f, 32.0f);

        List<KNearestNeighborSearch.KNNCandidate<String>> smallResults = ParallelOctreeOperations.kNearestNeighborsParallel(
        queryPoint, 2, smallOctree, parallelConfig);

        // Should still work correctly even if processed sequentially
        assertEquals(2, smallResults.size());
        assertTrue(smallResults.get(0).distance <= smallResults.get(1).distance);

        // Large octree (above threshold) - should use parallel
        List<KNearestNeighborSearch.KNNCandidate<String>> largeResults = ParallelOctreeOperations.kNearestNeighborsParallel(
        queryPoint, 5, largeOctree, parallelConfig);

        assertEquals(5, largeResults.size());
        assertTrue(largeResults.get(0).distance <= largeResults.get(4).distance);
    }
}
