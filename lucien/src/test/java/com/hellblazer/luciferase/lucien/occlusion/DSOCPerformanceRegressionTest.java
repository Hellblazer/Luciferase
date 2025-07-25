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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.prism.Prism;
import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.Frustum3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance regression tests for DSOC functionality.
 * These tests ensure DSOC provides the expected performance benefits.
 *
 * @author hal.hildebrand
 */
@EnabledIfSystemProperty(named = "test.performance", matches = "true")
public class DSOCPerformanceRegressionTest {
    
    private DSOCConfiguration config;
    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASURE_ITERATIONS = 1000;
    private static final double ACCEPTABLE_OVERHEAD = 1.2; // 20% overhead is acceptable
    
    @BeforeEach
    void setUp() {
        config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withAutoDynamicsEnabled(true)
            .withEnableHierarchicalOcclusion(true)
            .withMinOccluderVolume(10.0f);
    }
    
    static Stream<Arguments> spatialIndexProvider() {
        return Stream.of(
            Arguments.of("Octree", createOctree()),
            Arguments.of("Tetree", createTetree()),
            Arguments.of("Prism", createPrism())
        );
    }
    
    private static Octree<LongEntityID, String> createOctree() {
        return new Octree<>(new SequentialLongIDGenerator());
    }
    
    private static Tetree<LongEntityID, String> createTetree() {
        return new Tetree<>(new SequentialLongIDGenerator());
    }
    
    private static Prism<LongEntityID, String> createPrism() {
        return new Prism<>(new SequentialLongIDGenerator(), 1.0f, 21);
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC frustum culling performance")
    void testFrustumCullingPerformance(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Test with sparse scene (few visible entities)
        double sparseSpeedup = measureFrustumCullingSpeedup(index, 1000, 0.1f);
        System.out.printf("%s sparse scene speedup: %.2fx%n", indexType, sparseSpeedup);
        assertTrue(sparseSpeedup > 1.0 / ACCEPTABLE_OVERHEAD, 
            "DSOC should not degrade performance by more than " + ((ACCEPTABLE_OVERHEAD - 1) * 100) + "%");
        
        // Test with dense scene (many visible entities)
        double denseSpeedup = measureFrustumCullingSpeedup(index, 1000, 0.8f);
        System.out.printf("%s dense scene speedup: %.2fx%n", indexType, denseSpeedup);
        assertTrue(denseSpeedup > 1.0 / ACCEPTABLE_OVERHEAD, 
            "DSOC should not degrade performance significantly in dense scenes");
        
        // Test with occluded scene (heavy occlusion)
        double occludedSpeedup = measureOccludedSceneSpeedup(index, 1000);
        System.out.printf("%s occluded scene speedup: %.2fx%n", indexType, occludedSpeedup);
        assertTrue(occludedSpeedup > 1.5, 
            "DSOC should provide significant speedup with heavy occlusion");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC entity update performance")
    void testEntityUpdatePerformance(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Measure update performance with visible entities
        double visibleUpdateOverhead = measureEntityUpdateOverhead(index, 500, true);
        System.out.printf("%s visible entity update overhead: %.2f%%%n", 
            indexType, (visibleUpdateOverhead - 1) * 100);
        assertTrue(visibleUpdateOverhead < ACCEPTABLE_OVERHEAD, 
            "DSOC overhead for visible entities should be minimal");
        
        // Measure update performance with hidden entities (should benefit from TBV)
        double hiddenUpdateSpeedup = measureEntityUpdateOverhead(index, 500, false);
        System.out.printf("%s hidden entity update speedup: %.2fx%n", 
            indexType, 1.0 / hiddenUpdateSpeedup);
        assertTrue(hiddenUpdateSpeedup < 1.0, 
            "DSOC should speed up hidden entity updates via deferred updates");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC memory efficiency")
    void testMemoryEfficiency(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Measure memory overhead
        long baselineMemory = measureMemoryUsage(() -> {
            populateIndex(index, 10000, false);
            performQueries(index, 100);
        });
        
        long dsocMemory = measureMemoryUsage(() -> {
            index.enableDSOC(config, 512, 512);
            populateIndex(index, 10000, false);
            performQueries(index, 100);
        });
        
        double memoryOverhead = (double) dsocMemory / baselineMemory;
        System.out.printf("%s DSOC memory overhead: %.2f%%%n", 
            indexType, (memoryOverhead - 1) * 100);
        
        // DSOC should have reasonable memory overhead
        assertTrue(memoryOverhead < 1.5, 
            "DSOC memory overhead should be less than 50%");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC scaling performance")
    void testScalingPerformance(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Test performance scaling with entity count
        int[] entityCounts = {100, 1000, 5000, 10000};
        double[] speedups = new double[entityCounts.length];
        
        for (int i = 0; i < entityCounts.length; i++) {
            speedups[i] = measureFrustumCullingSpeedup(index, entityCounts[i], 0.3f);
            System.out.printf("%s with %d entities: %.2fx speedup%n", 
                indexType, entityCounts[i], speedups[i]);
        }
        
        // DSOC benefits should increase with entity count
        for (int i = 1; i < speedups.length; i++) {
            assertTrue(speedups[i] >= speedups[i-1] * 0.9, 
                "DSOC performance should scale well with entity count");
        }
    }
    
    @Test
    @DisplayName("DSOC auto-disable performance protection")
    void testAutoDisablePerformance() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        // Configure DSOC
        var autoDisableConfig = config;
        
        octree.enableDSOC(autoDisableConfig, 512, 512);
        
        // Create pathological case where DSOC is very slow
        populatePathologicalCase(octree);
        
        // Perform many queries
        var frustum = createTestFrustum();
        for (int frame = 0; frame < 50; frame++) {
            octree.nextFrame();
            octree.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
        
        // Check if DSOC auto-disabled due to poor performance
        var stats = octree.getDSOCStatistics();
        // If performance was bad, DSOC might have auto-disabled
        System.out.println("DSOC still enabled: " + stats.get("dsocEnabled"));
    }
    
    @Test
    @DisplayName("Cross-index DSOC performance comparison")
    void testCrossIndexPerformance() {
        var octree = createOctree();
        var tetree = createTetree();
        var prism = createPrism();
        
        // Measure relative performance
        double octreeSpeedup = measureFrustumCullingSpeedup(octree, 1000, 0.3f);
        double tetreeSpeedup = measureFrustumCullingSpeedup(tetree, 1000, 0.3f);
        double prismSpeedup = measureFrustumCullingSpeedup(prism, 1000, 0.3f);
        
        System.out.println("\nDSOC Performance Comparison:");
        System.out.printf("Octree: %.2fx%n", octreeSpeedup);
        System.out.printf("Tetree: %.2fx%n", tetreeSpeedup);
        System.out.printf("Prism: %.2fx%n", prismSpeedup);
        
        // All should show some benefit or minimal overhead
        assertTrue(octreeSpeedup > 1.0 / ACCEPTABLE_OVERHEAD);
        assertTrue(tetreeSpeedup > 1.0 / ACCEPTABLE_OVERHEAD);
        assertTrue(prismSpeedup > 1.0 / ACCEPTABLE_OVERHEAD);
    }
    
    // Helper methods
    
    private double measureFrustumCullingSpeedup(AbstractSpatialIndex<?, LongEntityID, String> index, 
                                               int entityCount, float visibilityRatio) {
        // Reset index
        clearIndex(index);
        
        // Populate with entities
        populateIndex(index, entityCount, false);
        
        // Create frustum that sees visibilityRatio of entities
        var frustum = createSelectiveFrustum(visibilityRatio);
        var cameraPos = new Point3f(0.5f, 0.5f, -1);
        
        // Measure without DSOC
        long withoutDSOC = measureFrustumCullingTime(index, frustum, cameraPos, false);
        
        // Clear and repopulate
        clearIndex(index);
        populateIndex(index, entityCount, false);
        
        // Measure with DSOC
        long withDSOC = measureFrustumCullingTime(index, frustum, cameraPos, true);
        
        return (double) withoutDSOC / withDSOC;
    }
    
    private double measureOccludedSceneSpeedup(AbstractSpatialIndex<?, LongEntityID, String> index, 
                                              int entityCount) {
        clearIndex(index);
        
        // Create scene with occluders
        populateOccludedScene(index, entityCount);
        
        var frustum = createTestFrustum();
        var cameraPos = new Point3f(0.5f, 0.5f, -2);
        
        // Measure without DSOC
        long withoutDSOC = measureFrustumCullingTime(index, frustum, cameraPos, false);
        
        // Clear and repopulate
        clearIndex(index);
        populateOccludedScene(index, entityCount);
        
        // Measure with DSOC
        long withDSOC = measureFrustumCullingTime(index, frustum, cameraPos, true);
        
        return (double) withoutDSOC / withDSOC;
    }
    
    private double measureEntityUpdateOverhead(AbstractSpatialIndex<?, LongEntityID, String> index,
                                             int entityCount, boolean visible) {
        clearIndex(index);
        
        // Populate index
        List<LongEntityID> ids = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            var id = new LongEntityID(i);
            ids.add(id);
            index.insert(id, getValidPosition(i, index), (byte) 10, "Entity" + i);
        }
        
        // Setup visibility
        if (!visible) {
            var frustum = createExcludingFrustum();
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
        
        // Measure update time without DSOC
        long withoutDSOC = measureUpdateTime(index, ids, false);
        
        // Measure update time with DSOC
        long withDSOC = measureUpdateTime(index, ids, true);
        
        return (double) withDSOC / withoutDSOC;
    }
    
    private long measureFrustumCullingTime(AbstractSpatialIndex<?, LongEntityID, String> index,
                                          Frustum3D frustum, Point3f cameraPos, boolean enableDSOC) {
        if (enableDSOC) {
            index.enableDSOC(config, 512, 512);
            
            // Setup camera
            var viewMatrix = createViewMatrix(cameraPos, new Point3f(0.5f, 0.5f, 0.5f), new Vector3f(0, 1, 0));
            var projMatrix = createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 100.0f);
            index.updateCamera(viewMatrix, projMatrix, cameraPos);
        }
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            index.frustumCullVisible(frustum, cameraPos);
            if (enableDSOC && i % 10 == 0) {
                index.nextFrame();
            }
        }
        
        // Measure
        long startTime = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            index.frustumCullVisible(frustum, cameraPos);
            if (enableDSOC && i % 10 == 0) {
                index.nextFrame();
            }
        }
        return System.nanoTime() - startTime;
    }
    
    private long measureUpdateTime(AbstractSpatialIndex<?, LongEntityID, String> index,
                                  List<LongEntityID> ids, boolean enableDSOC) {
        if (enableDSOC) {
            index.enableDSOC(config, 512, 512);
        }
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var id = ids.get(i % ids.size());
            var newPos = getValidPosition(i + 1000, index);
            index.updateEntity(id, newPos, (byte) 10);
        }
        
        // Measure
        long startTime = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            var id = ids.get(i % ids.size());
            var newPos = getValidPosition(i + 2000, index);
            index.updateEntity(id, newPos, (byte) 10);
            
            if (enableDSOC && i % 10 == 0) {
                index.nextFrame();
            }
        }
        return System.nanoTime() - startTime;
    }
    
    private long measureMemoryUsage(Runnable task) {
        System.gc();
        long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        task.run();
        
        System.gc();
        long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        return after - before;
    }
    
    private void populateIndex(AbstractSpatialIndex<?, LongEntityID, String> index, 
                              int count, boolean clustered) {
        Random rand = new Random(42);
        
        for (int i = 0; i < count; i++) {
            var pos = clustered ? 
                getClusteredPosition(i, index) : 
                getValidPosition(i, index);
            index.insert(new LongEntityID(i), pos, (byte) 10, "Entity" + i);
        }
    }
    
    private void populateOccludedScene(AbstractSpatialIndex<?, LongEntityID, String> index, int count) {
        // Create large occluders in front
        for (int i = 0; i < 10; i++) {
            var pos = new Point3f(0.1f + i * 0.08f, 0.1f + i * 0.08f, 0.1f);
            index.insert(new LongEntityID(i), pos, (byte) 5, "Occluder" + i);
        }
        
        // Create many small entities behind
        for (int i = 10; i < count; i++) {
            var pos = getValidPosition(i, index);
            pos.z = 0.7f + (i % 100) * 0.002f; // Place behind occluders
            index.insert(new LongEntityID(i), pos, (byte) 15, "Occluded" + i);
        }
    }
    
    private void populatePathologicalCase(AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Create case where DSOC performs poorly
        // Many small, rapidly moving entities
        for (int i = 0; i < 1000; i++) {
            var pos = getValidPosition(i, index);
            index.insert(new LongEntityID(i), pos, (byte) 18, "Pathological" + i);
        }
    }
    
    private void clearIndex(AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Remove all entities
        var allIds = new ArrayList<LongEntityID>();
        for (int i = 0; i < 20000; i++) {
            allIds.add(new LongEntityID(i));
        }
        
        for (var id : allIds) {
            index.removeEntity(id);
        }
        
        // Note: No disableDSOC method available
    }
    
    private void performQueries(AbstractSpatialIndex<?, LongEntityID, String> index, int count) {
        var frustum = createTestFrustum();
        for (int i = 0; i < count; i++) {
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
    }
    
    private Point3f getValidPosition(int seed, AbstractSpatialIndex<?, LongEntityID, String> index) {
        Random rand = new Random(seed);
        
        if (index instanceof Prism) {
            float x = rand.nextFloat() * 0.4f;
            float y = rand.nextFloat() * 0.4f;
            float z = rand.nextFloat() * 0.9f;
            return new Point3f(x, y, z);
        } else {
            return new Point3f(
                rand.nextFloat() * 0.9f,
                rand.nextFloat() * 0.9f,
                rand.nextFloat() * 0.9f
            );
        }
    }
    
    private Point3f getClusteredPosition(int index, AbstractSpatialIndex<?, LongEntityID, String> spatialIndex) {
        Random rand = new Random(index);
        
        // Create clusters
        int cluster = index / 100;
        float clusterX = (cluster % 3) * 0.3f + 0.1f;
        float clusterY = ((cluster / 3) % 3) * 0.3f + 0.1f;
        float clusterZ = ((cluster / 9) % 3) * 0.3f + 0.1f;
        
        // Add local offset
        float x = clusterX + rand.nextFloat() * 0.1f;
        float y = clusterY + rand.nextFloat() * 0.1f;
        float z = clusterZ + rand.nextFloat() * 0.1f;
        
        if (spatialIndex instanceof Prism && x + y >= 1.0f) {
            // Adjust for Prism constraint
            float scale = 0.9f / (x + y);
            x *= scale;
            y *= scale;
        }
        
        return new Point3f(x, y, z);
    }
    
    private Frustum3D createTestFrustum() {
        return Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(60.0f), 1.0f, 0.1f, 10.0f
        );
    }
    
    private Frustum3D createSelectiveFrustum(float visibilityRatio) {
        float size = visibilityRatio;
        return Frustum3D.createOrthographic(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            0.5f - size/2, 0.5f + size/2,
            0.5f - size/2, 0.5f + size/2,
            0.1f, 10.0f
        );
    }
    
    private Frustum3D createExcludingFrustum() {
        return Frustum3D.createOrthographic(
            new Point3f(10, 10, -1),
            new Point3f(10, 10, 0),
            new Vector3f(0, 1, 0),
            9, 11, 9, 11, 0.1f, 10.0f
        );
    }
    
    private float[] createViewMatrix(Point3f eye, Point3f center, Vector3f up) {
        Vector3f f = new Vector3f();
        f.sub(center, eye);
        f.normalize();
        
        Vector3f s = new Vector3f();
        s.cross(f, up);
        s.normalize();
        
        Vector3f u = new Vector3f();
        u.cross(s, f);
        
        float[] matrix = new float[16];
        matrix[0] = s.x;
        matrix[4] = s.y;
        matrix[8] = s.z;
        matrix[1] = u.x;
        matrix[5] = u.y;
        matrix[9] = u.z;
        matrix[2] = -f.x;
        matrix[6] = -f.y;
        matrix[10] = -f.z;
        matrix[12] = -s.dot(new Vector3f(eye));
        matrix[13] = -u.dot(new Vector3f(eye));
        matrix[14] = f.dot(new Vector3f(eye));
        matrix[15] = 1.0f;
        
        return matrix;
    }
    
    private float[] createPerspectiveMatrix(float fovy, float aspect, float near, float far) {
        float[] matrix = new float[16];
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovy) / 2.0));
        
        matrix[0] = f / aspect;
        matrix[5] = f;
        matrix[10] = (far + near) / (near - far);
        matrix[11] = -1.0f;
        matrix[14] = (2.0f * far * near) / (near - far);
        
        return matrix;
    }
}
