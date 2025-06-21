/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for spatial index query operations.
 * Tests range search, k-NN, ray intersection, and frustum culling performance.
 *
 * @author hal.hildebrand
 */
public abstract class SpatialIndexQueryPerformanceTest<ID extends EntityID, Content> extends AbstractSpatialIndexPerformanceTest<ID, Content> {
    
    private static final int[] TREE_SIZES = {1000, 10000, 100000, 1000000};
    private static final float[] QUERY_BOX_PERCENTAGES = {0.01f, 0.05f, 0.1f, 0.25f}; // 1%, 5%, 10%, 25% of space
    private static final int[] K_VALUES = {1, 5, 10, 20, 50, 100};
    private static final byte DEFAULT_LEVEL = 10; // Reasonable default level
    
    @ParameterizedTest(name = "TreeSize={0}")
    @ValueSource(ints = {10000, 100000, 1000000})
    @DisplayName("Test range search performance with varying tree sizes")
    void testRangeSearchPerformance(int treeSize) {
        // Create and populate index
        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> entities = generateTestEntities(treeSize, SpatialDistribution.UNIFORM_RANDOM);
        
        for (TestEntity entity : entities) {
            index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        // Test different query box sizes
        for (float percentage : QUERY_BOX_PERCENTAGES) {
            float boxSize = (DEFAULT_BOUNDS.maxX() - DEFAULT_BOUNDS.minX()) * percentage;
            
            // Generate random query boxes
            List<Spatial.Cube> queryBoxes = generateRandomQueryBoxes(100, boxSize);
            
            PerformanceMetrics metrics = measure(
                String.format("range_search_%dpct", (int)(percentage * 100)),
                queryBoxes.size(),
                () -> {
                    for (Spatial.Cube queryBox : queryBoxes) {
                        List<ID> results = index.entitiesInRegion(queryBox);
                        // Force evaluation of results
                        int count = results.size();
                    }
                }
            );
            
            metrics.getAdditionalMetrics().put("tree_size", treeSize);
            metrics.getAdditionalMetrics().put("query_box_percentage", percentage);
            performanceResults.add(metrics);
            
            System.out.printf("Range search in %d-entity tree with %.0f%% query box: %.2fms for %d queries (%.2f queries/sec)%n",
                treeSize, percentage * 100, metrics.getElapsedMillis(), queryBoxes.size(), 
                metrics.getOperationsPerSecond());
        }
    }
    
    @ParameterizedTest(name = "k={0}")
    @ValueSource(ints = {1, 5, 10, 20, 50, 100})
    @DisplayName("Test k-NN search performance with varying k values")
    void testKNNSearchPerformance(int k) {
        int treeSize = 100000;
        
        // Create and populate index
        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> entities = generateTestEntities(treeSize, SpatialDistribution.UNIFORM_RANDOM);
        
        for (TestEntity entity : entities) {
            index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        // Generate random query points
        List<Point3f> queryPoints = generateRandomQueryPoints(100);
        
        PerformanceMetrics metrics = measure(
            String.format("knn_search_k%d", k),
            queryPoints.size(),
            () -> {
                for (Point3f queryPoint : queryPoints) {
                    List<ID> results = index.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
                    // Verify we got the right number of results
                    assertEquals(Math.min(k, treeSize), results.size());
                }
            }
        );
        
        metrics.getAdditionalMetrics().put("k", k);
        metrics.getAdditionalMetrics().put("tree_size", treeSize);
        performanceResults.add(metrics);
        
        System.out.printf("k-NN search (k=%d) in %d-entity tree: %.2fms for %d queries (%.2f queries/sec)%n",
            k, treeSize, metrics.getElapsedMillis(), queryPoints.size(), 
            metrics.getOperationsPerSecond());
    }
    
    @Test
    @DisplayName("Test ray intersection performance")
    void testRayIntersectionPerformance() {
        int treeSize = 100000;
        
        // Create and populate index
        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> entities = generateTestEntities(treeSize, SpatialDistribution.UNIFORM_RANDOM);
        
        for (TestEntity entity : entities) {
            index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        // Generate random rays
        List<com.hellblazer.luciferase.lucien.Ray3D> rays = generateRandomRays(1000);
        
        PerformanceMetrics metrics = measure(
            "ray_intersection",
            rays.size(),
            () -> {
                for (com.hellblazer.luciferase.lucien.Ray3D ray : rays) {
                    List<com.hellblazer.luciferase.lucien.SpatialIndex.RayIntersection<ID, Content>> results = 
                        index.rayIntersectAll(ray);
                }
            }
        );
        
        metrics.getAdditionalMetrics().put("tree_size", treeSize);
        performanceResults.add(metrics);
        
        System.out.printf("Ray intersection in %d-entity tree: %.2fms for %d rays (%.2f rays/sec)%n",
            treeSize, metrics.getElapsedMillis(), rays.size(), 
            metrics.getOperationsPerSecond());
    }
    
    @Test
    @DisplayName("Test frustum culling performance")
    void testFrustumCullingPerformance() {
        int treeSize = 100000;
        
        // Create and populate index
        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> entities = generateTestEntities(treeSize, SpatialDistribution.UNIFORM_RANDOM);
        
        for (TestEntity entity : entities) {
            index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        // Generate random view frustums
        List<Frustum3D> frustums = generateRandomFrustums(100);
        
        PerformanceMetrics metrics = measure(
            "frustum_culling",
            frustums.size(),
            () -> {
                for (Frustum3D frustum : frustums) {
                    // TODO: frustumCullVisible method doesn't exist yet
                    // This is a placeholder that just counts entities instead
                    List<ID> allEntities = new ArrayList<>();
                    index.nodes().forEach(node -> allEntities.addAll(node.entityIds()));
                    int count = allEntities.size(); // Just to consume the result
                }
            }
        );
        
        metrics.getAdditionalMetrics().put("tree_size", treeSize);
        performanceResults.add(metrics);
        
        System.out.printf("Frustum culling in %d-entity tree: %.2fms for %d frustums (%.2f frustums/sec)%n",
            treeSize, metrics.getElapsedMillis(), frustums.size(), 
            metrics.getOperationsPerSecond());
    }
    
    @Test
    @DisplayName("Test query performance scaling")
    void testQueryScaling() {
        for (int treeSize : TREE_SIZES) {
            // Create and populate index
            SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
            List<TestEntity> entities = generateTestEntities(treeSize, SpatialDistribution.UNIFORM_RANDOM);
            
            for (TestEntity entity : entities) {
                index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
            }
            
            // Fixed query parameters
            float queryBoxSize = (DEFAULT_BOUNDS.maxX() - DEFAULT_BOUNDS.minX()) * 0.1f; // 10% of space
            Spatial.Cube queryBox = new Spatial.Cube(450, 450, 450, queryBoxSize);
            
            // Measure single query time
            PerformanceMetrics metrics = measureAverage(
                "query_scaling",
                1,
                () -> {}, // No setup
                () -> {
                    List<ID> results = index.entitiesInRegion(queryBox);
                    int count = results.size(); // Force evaluation
                }
            );
            
            metrics.getAdditionalMetrics().put("tree_size", treeSize);
            performanceResults.add(metrics);
            
            System.out.printf("Query scaling - Tree size: %d, Query time: %.3fms%n",
                treeSize, metrics.getElapsedMillis());
        }
    }
    
    @Test
    @DisplayName("Test empty result query performance")
    void testEmptyResultQueries() {
        int treeSize = 100000;
        
        // Create and populate index
        SpatialIndex<ID, Content> index = createSpatialIndex(DEFAULT_BOUNDS, DEFAULT_MAX_DEPTH);
        List<TestEntity> entities = generateTestEntities(treeSize, SpatialDistribution.UNIFORM_RANDOM);
        
        for (TestEntity entity : entities) {
            index.insert((ID) entity.id, entity.position, DEFAULT_LEVEL, (Content) entity.content);
        }
        
        // Generate query boxes outside the bounds
        List<Spatial.Cube> emptyQueries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            emptyQueries.add(new Spatial.Cube(2000, 2000, 2000, 100));
        }
        
        PerformanceMetrics metrics = measure(
            "empty_result_queries",
            emptyQueries.size(),
            () -> {
                for (Spatial.Cube queryBox : emptyQueries) {
                    List<ID> results = index.entitiesInRegion(queryBox);
                    assertTrue(results.isEmpty());
                }
            }
        );
        
        performanceResults.add(metrics);
        
        System.out.printf("Empty result queries: %.2fms for %d queries (%.2f queries/sec)%n",
            metrics.getElapsedMillis(), emptyQueries.size(), 
            metrics.getOperationsPerSecond());
    }
    
    // Helper methods
    
    private List<Spatial.Cube> generateRandomQueryBoxes(int count, float boxSize) {
        List<Spatial.Cube> boxes = new ArrayList<>(count);
        float maxCoord = DEFAULT_BOUNDS.maxX() - boxSize;
        
        for (int i = 0; i < count; i++) {
            float x = DEFAULT_BOUNDS.minX() + random.nextFloat() * (maxCoord - DEFAULT_BOUNDS.minX());
            float y = DEFAULT_BOUNDS.minY() + random.nextFloat() * (maxCoord - DEFAULT_BOUNDS.minY());
            float z = DEFAULT_BOUNDS.minZ() + random.nextFloat() * (maxCoord - DEFAULT_BOUNDS.minZ());
            
            boxes.add(new Spatial.Cube(x, y, z, boxSize));
        }
        
        return boxes;
    }
    
    private List<Point3f> generateRandomQueryPoints(int count) {
        List<Point3f> points = new ArrayList<>(count);
        
        for (int i = 0; i < count; i++) {
            points.add(new Point3f(
                DEFAULT_BOUNDS.minX() + random.nextFloat() * (DEFAULT_BOUNDS.maxX() - DEFAULT_BOUNDS.minX()),
                DEFAULT_BOUNDS.minY() + random.nextFloat() * (DEFAULT_BOUNDS.maxY() - DEFAULT_BOUNDS.minY()),
                DEFAULT_BOUNDS.minZ() + random.nextFloat() * (DEFAULT_BOUNDS.maxZ() - DEFAULT_BOUNDS.minZ())
            ));
        }
        
        return points;
    }
    
    private List<com.hellblazer.luciferase.lucien.Ray3D> generateRandomRays(int count) {
        List<com.hellblazer.luciferase.lucien.Ray3D> rays = new ArrayList<>(count);
        
        for (int i = 0; i < count; i++) {
            Point3f origin = new Point3f(
                DEFAULT_BOUNDS.minX() + random.nextFloat() * (DEFAULT_BOUNDS.maxX() - DEFAULT_BOUNDS.minX()),
                DEFAULT_BOUNDS.minY() + random.nextFloat() * (DEFAULT_BOUNDS.maxY() - DEFAULT_BOUNDS.minY()),
                DEFAULT_BOUNDS.minZ() + random.nextFloat() * (DEFAULT_BOUNDS.maxZ() - DEFAULT_BOUNDS.minZ())
            );
            
            Vector3f direction = new Vector3f(
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1
            );
            direction.normalize();
            
            rays.add(new com.hellblazer.luciferase.lucien.Ray3D(origin, direction, 1000.0f));
        }
        
        return rays;
    }
    
    private List<Frustum3D> generateRandomFrustums(int count) {
        List<Frustum3D> frustums = new ArrayList<>(count);
        
        for (int i = 0; i < count; i++) {
            // Random camera position
            Point3f eye = new Point3f(
                DEFAULT_BOUNDS.minX() + random.nextFloat() * (DEFAULT_BOUNDS.maxX() - DEFAULT_BOUNDS.minX()),
                DEFAULT_BOUNDS.minY() + random.nextFloat() * (DEFAULT_BOUNDS.maxY() - DEFAULT_BOUNDS.minY()),
                DEFAULT_BOUNDS.minZ() + random.nextFloat() * (DEFAULT_BOUNDS.maxZ() - DEFAULT_BOUNDS.minZ())
            );
            
            // Random look direction
            Vector3f forward = new Vector3f(
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1
            );
            forward.normalize();
            
            // Create look-at point
            Point3f lookAt = new Point3f(
                eye.x + forward.x * 100,
                eye.y + forward.y * 100,
                eye.z + forward.z * 100
            );
            
            // Create frustum with typical parameters
            frustums.add(Frustum3D.createPerspective(
                eye,
                lookAt,
                new Vector3f(0, 1, 0), // up
                (float) Math.toRadians(60.0), // field of view in radians
                1.333f, // aspect ratio
                1.0f, // near
                1000.0f // far
            ));
        }
        
        return frustums;
    }
}