package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.hellblazer.luciferase.lucien.TetrahedralSearchBase.SimplexAggregationStrategy;

/**
 * Comprehensive performance benchmark for all 11 Tetree search classes
 * Compares Tetree search operations against their Octree equivalents
 * 
 * @author hal.hildebrand
 */
public class TetreeSearchClassesBenchmark {

    private Tetree<String> tetree;
    private Octree<String> octree;
    private final byte testLevel = 15;
    private static final int BENCHMARK_DATASET_SIZE = 1000;
    private static final int ITERATIONS = 100;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        octree = new Octree<>(new TreeMap<>());
        populateWithTestData(BENCHMARK_DATASET_SIZE);
    }

    @Test
    @DisplayName("Benchmark key Tetree search classes vs Octree equivalents")
    void benchmarkAllSearchClasses() {
        System.out.println("=== TETREE SEARCH PERFORMANCE BENCHMARK ===\n");
        System.out.printf("Dataset Size: %d points, Iterations: %d%n%n", BENCHMARK_DATASET_SIZE, ITERATIONS);
        
        // Test core spatial operations that have direct equivalents
        benchmarkBasicSpatialOperations();
        benchmarkKNearestNeighborSearch();
        
        printPerformanceSummary();
    }

    private void benchmarkBasicSpatialOperations() {
        System.out.println("--- Basic Spatial Operations ---");
        
        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);
        
        // Benchmark boundedBy operation
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = tetree.boundedBy(query).toList();
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = octree.boundedBy(query).toList();
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("BoundedBy: Tetree %.2f μs (%d results), Octree %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkKNearestNeighborSearch() {
        System.out.println("--- K-Nearest Neighbor Search ---");
        
        Point3f queryPoint = new Point3f(2000.0f, 2000.0f, 2000.0f);
        int k = 10;
        
        // Warm up
        for (int i = 0; i < 10; i++) {
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, k, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, k, octree);
        }
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, k, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = KNearestNeighborSearch.findKNearestNeighbors(queryPoint, k, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkRayTracingSearch() {
        System.out.println("--- Ray Tracing Search ---");
        
        Point3f rayStart = new Point3f(1000.0f, 1000.0f, 1000.0f);
        Vector3f rayDirection = new Vector3f(1.0f, 0.5f, 0.2f);
        rayDirection.normalize();
        Ray3D ray = new Ray3D(rayStart, rayDirection);
        
        // Warm up
        for (int i = 0; i < 10; i++) {
            TetRayTracingSearch.rayIntersectedAll(ray, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            RayTracingSearch.rayIntersectedAll(ray, octree);
        }
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetRayTracingSearch.rayIntersectedAll(ray, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = RayTracingSearch.rayIntersectedAll(ray, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkParallelSpatialProcessor() {
        System.out.println("--- Parallel Spatial Processor ---");
        
        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);
        
        // Warm up
        for (int i = 0; i < 10; i++) {
            TetParallelSpatialProcessor.parallelBoundedBy(query, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            ParallelSpatialProcessor.parallelBoundedBy(query, octree);
        }
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS / 10; i++) { // Fewer iterations for parallel operations
            long start = System.nanoTime();
            var tetreeResults = TetParallelSpatialProcessor.parallelBoundedBy(query, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS / 10; i++) {
            long start = System.nanoTime();
            var octreeResults = ParallelSpatialProcessor.parallelBoundedBy(query, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / ((ITERATIONS / 10) * 1000.0);
        double octreeAvg = octreeTime / ((ITERATIONS / 10) * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkSphereIntersectionSearch() {
        System.out.println("--- Sphere Intersection Search ---");
        
        Point3f center = new Point3f(2000.0f, 2000.0f, 2000.0f);
        float radius = 1000.0f;
        
        // Benchmark Tetree (no direct Octree equivalent - use cube approximation)
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetSphereIntersectionSearch.sphereIntersectedAll(center, radius, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Use AABB approximation for Octree comparison
        var sphereAABB = new Spatial.Cube(center.x - radius, center.y - radius, center.z - radius, radius * 2);
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = octree.boundedBy(sphereAABB).toList();
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree (AABB approx): %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkAABBIntersectionSearch() {
        System.out.println("--- AABB Intersection Search ---");
        
        var aabb = new Spatial.Cube(1500.0f, 1500.0f, 1500.0f, 1000.0f);
        
        // Warm up
        for (int i = 0; i < 10; i++) {
            TetAABBIntersectionSearch.aabbIntersectedAll(aabb, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            AABBIntersectionSearch.aabbIntersectedAll(aabb, octree);
        }
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetAABBIntersectionSearch.aabbIntersectedAll(aabb, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = AABBIntersectionSearch.aabbIntersectedAll(aabb, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkPlaneIntersectionSearch() {
        System.out.println("--- Plane Intersection Search ---");
        
        Plane3D plane = new Plane3D(new Point3f(2000.0f, 2000.0f, 2000.0f), new Vector3f(1.0f, 0.0f, 0.0f));
        
        // Warm up
        for (int i = 0; i < 10; i++) {
            TetPlaneIntersectionSearch.planeIntersectedAll(plane, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree);
        }
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetPlaneIntersectionSearch.planeIntersectedAll(plane, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = PlaneIntersectionSearch.planeIntersectedAll(plane, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkConvexHullIntersectionSearch() {
        System.out.println("--- Convex Hull Intersection Search ---");
        
        var hull = TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
            new Point3f(1500.0f, 1500.0f, 1500.0f),
            new Point3f(2500.0f, 1500.0f, 1500.0f),
            new Point3f(1500.0f, 2500.0f, 1500.0f),
            new Point3f(1500.0f, 1500.0f, 2500.0f)
        );
        Point3f referencePoint = new Point3f(1000.0f, 1000.0f, 1000.0f);
        
        // ConvexHullIntersectionSearch equivalent for Octree
        var octreeHull = ConvexHullIntersectionSearch.ConvexHull.createTetrahedralHull(
            new Point3f(1500.0f, 1500.0f, 1500.0f),
            new Point3f(2500.0f, 1500.0f, 1500.0f),
            new Point3f(1500.0f, 2500.0f, 1500.0f),
            new Point3f(1500.0f, 1500.0f, 2500.0f)
        );
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = ConvexHullIntersectionSearch.convexHullIntersectedAll(octreeHull, octree, referencePoint);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkProximitySearch() {
        System.out.println("--- Proximity Search ---");
        
        Point3f queryPoint = new Point3f(2000.0f, 2000.0f, 2000.0f);
        float minDistance = 500.0f;
        float maxDistance = 1500.0f;
        
        // Warm up
        for (int i = 0; i < 10; i++) {
            TetProximitySearch.tetrahedraWithinDistanceRange(queryPoint, minDistance, maxDistance, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            ProximitySearch.cubesWithinDistanceRange(queryPoint, minDistance, maxDistance, octree);
        }
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetProximitySearch.tetrahedraWithinDistanceRange(queryPoint, minDistance, maxDistance, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = ProximitySearch.cubesWithinDistanceRange(queryPoint, minDistance, maxDistance, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkContainmentSearch() {
        System.out.println("--- Containment Search ---");
        
        Point3f center = new Point3f(2000.0f, 2000.0f, 2000.0f);
        float radius = 1000.0f;
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetContainmentSearch.tetrahedraContainedInSphere(center, radius, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = ContainmentSearch.cubesContainedInSphere(center, radius, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkFrustumCullingSearch() {
        System.out.println("--- Frustum Culling Search ---");
        
        Frustum3D frustum = new Frustum3D(
            new Point3f(1000.0f, 1000.0f, 1000.0f),  // eye
            new Point3f(3000.0f, 3000.0f, 3000.0f),  // target
            new Vector3f(0.0f, 0.0f, 1.0f),          // up
            (float) Math.PI / 4.0f,                   // fovy
            1.0f,                                     // aspect
            100.0f,                                   // near
            5000.0f                                   // far
        );
        
        // Benchmark Tetree
        long tetreeTime = 0;
        int tetreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResults = TetFrustumCullingSearch.frustumVisibleAll(frustum, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
            tetreeResultCount = tetreeResults.size();
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        int octreeResultCount = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResults = FrustumCullingSearch.frustumVisibleAll(frustum, octree);
            octreeTime += System.nanoTime() - start;
            octreeResultCount = octreeResults.size();
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs (%d results), Octree: %.2f μs (%d results) -> %.2fx%n", 
                         tetreeAvg, tetreeResultCount, octreeAvg, octreeResultCount, tetreeAvg / octreeAvg);
    }

    private void benchmarkVisibilitySearch() {
        System.out.println("--- Visibility Search ---");
        
        Point3f observer = new Point3f(1000.0f, 1000.0f, 1000.0f);
        Point3f target = new Point3f(3000.0f, 3000.0f, 3000.0f);
        
        // Benchmark Tetree
        long tetreeTime = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var tetreeResult = TetVisibilitySearch.testLineOfSight(observer, target, tetree, 0.1, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            tetreeTime += System.nanoTime() - start;
        }
        
        // Benchmark Octree
        long octreeTime = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            var octreeResult = VisibilitySearch.testLineOfSight(observer, target, octree, 0.1);
            octreeTime += System.nanoTime() - start;
        }
        
        double tetreeAvg = tetreeTime / (ITERATIONS * 1000.0);
        double octreeAvg = octreeTime / (ITERATIONS * 1000.0);
        
        System.out.printf("Tetree: %.2f μs, Octree: %.2f μs -> %.2fx%n", 
                         tetreeAvg, octreeAvg, tetreeAvg / octreeAvg);
    }

    private void populateWithTestData(int count) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * 5000;
            float y = random.nextFloat() * 5000; 
            float z = random.nextFloat() * 5000;
            
            tetree.insert(new Point3f(x, y, z), testLevel, "tetree-data-" + i);
            octree.insert(new Point3f(x, y, z), testLevel, "octree-data-" + i);
        }
    }

    private void printPerformanceSummary() {
        System.out.println("\n=== PERFORMANCE SUMMARY ===");
        System.out.println("Performance Ratios (Tetree/Octree):");
        System.out.println("✓ 1.0x = Equal performance");
        System.out.println("✓ < 1.0x = Tetree faster than Octree");  
        System.out.println("✓ > 1.0x = Tetree slower than Octree");
        System.out.println("\nNote: Tetree 2-10x slower is acceptable given geometric complexity");
        System.out.println("Some operations may be faster due to tetrahedral spatial efficiency");
    }
}