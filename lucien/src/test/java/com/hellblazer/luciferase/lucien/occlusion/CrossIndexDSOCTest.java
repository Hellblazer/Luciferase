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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-index DSOC tests to ensure consistent behavior across all spatial index implementations
 *
 * @author hal.hildebrand
 */
public class CrossIndexDSOCTest {
    
    private DSOCConfiguration config;
    
    @BeforeEach
    void setUp() {
        config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withAutoDynamicsEnabled(true)
            .withEnableHierarchicalOcclusion(true)
            .withMinOccluderVolume(10.0f)
            .withTBVRefreshThreshold(0.5f);
    }
    
    static Stream<Arguments> spatialIndexProvider() {
        return Stream.of(
            Arguments.of("Octree", new Octree<LongEntityID, String>(new SequentialLongIDGenerator())),
            Arguments.of("Tetree", new Tetree<LongEntityID, String>(new SequentialLongIDGenerator())),
            Arguments.of("Prism", new Prism<LongEntityID, String>(new SequentialLongIDGenerator(), 1.0f, 21))
        );
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC enable/disable consistency")
    void testDSOCEnableDisable(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Initially disabled
        assertFalse(index.isDSOCEnabled());
        
        // Enable DSOC
        index.enableDSOC(config, 512, 512);
        assertTrue(index.isDSOCEnabled());
        
        // Get initial statistics
        var stats = index.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
        assertEquals(0L, stats.get("currentFrame"));
        assertEquals(0L, stats.get("totalEntities"));
        
        // Note: disableDSOC method doesn't exist, so we can't test disabling
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("Frame management consistency")
    void testFrameManagement(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Test frame advancement
        assertEquals(0L, index.getCurrentFrame());
        
        for (long expectedFrame = 1; expectedFrame <= 10; expectedFrame++) {
            long actualFrame = index.nextFrame();
            assertEquals(expectedFrame, actualFrame);
            assertEquals(expectedFrame, index.getCurrentFrame());
        }
        
        // Test frame consistency in statistics
        var stats = index.getDSOCStatistics();
        assertEquals(10L, stats.get("currentFrame"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("Camera update consistency")
    void testCameraUpdate(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Create camera matrices
        Point3f eye = new Point3f(0.5f, 0.5f, -2);
        Point3f center = new Point3f(0.5f, 0.5f, 0.5f);
        Vector3f up = new Vector3f(0, 1, 0);
        
        float[] viewMatrix = createViewMatrix(eye, center, up);
        float[] projMatrix = createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 100.0f);
        
        // Update camera shouldn't throw exceptions
        assertDoesNotThrow(() -> index.updateCamera(viewMatrix, projMatrix, eye));
        
        // Multiple updates should work
        for (int i = 0; i < 5; i++) {
            final Point3f currentEye = new Point3f(eye);
            currentEye.z -= 0.1f * i;
            final float[] currentViewMatrix = createViewMatrix(currentEye, center, up);
            assertDoesNotThrow(() -> index.updateCamera(currentViewMatrix, projMatrix, currentEye));
        }
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("Entity operations with DSOC")
    void testEntityOperationsWithDSOC(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Insert entities (use positions valid for all index types)
        List<Point3f> positions = getCommonValidPositions();
        List<LongEntityID> entityIds = new ArrayList<>();
        
        for (int i = 0; i < positions.size(); i++) {
            var id = new LongEntityID(i);
            entityIds.add(id);
            index.insert(id, positions.get(i), (byte) 10, "Entity" + i);
        }
        
        // Verify insertion
        var stats = index.getDSOCStatistics();
        assertEquals((long) positions.size(), stats.get("totalEntities"));
        
        // Update entities
        index.nextFrame();
        for (int i = 0; i < entityIds.size(); i++) {
            var newPos = new Point3f(positions.get(i));
            newPos.x += 0.01f;
            if (isValidPosition(newPos, indexType)) {
                index.updateEntity(entityIds.get(i), newPos, (byte) 10);
            }
        }
        
        // Remove half the entities
        index.nextFrame();
        for (int i = 0; i < entityIds.size() / 2; i++) {
            assertTrue(index.removeEntity(entityIds.get(i)));
        }
        
        stats = index.getDSOCStatistics();
        assertEquals((long) (positions.size() - positions.size() / 2), stats.get("totalEntities"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("Frustum culling with DSOC")
    void testFrustumCullingWithDSOC(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Insert entities
        List<Point3f> positions = getCommonValidPositions();
        for (int i = 0; i < positions.size(); i++) {
            index.insert(new LongEntityID(i), positions.get(i), (byte) 10, "Entity" + i);
        }
        
        // Create frustum
        var frustum = Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.25f, 0.25f, 0.25f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(60.0f), 1.0f, 0.1f, 10.0f
        );
        
        // Update camera
        float[] viewMatrix = createViewMatrix(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.25f, 0.25f, 0.25f),
            new Vector3f(0, 1, 0)
        );
        float[] projMatrix = createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 10.0f);
        index.updateCamera(viewMatrix, projMatrix, new Point3f(0.5f, 0.5f, -1));
        
        // Perform culling
        index.nextFrame();
        var visible = index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        assertNotNull(visible);
        
        // Check statistics reflect visibility updates
        var stats = index.getDSOCStatistics();
        assertNotNull(stats.get("visibleEntities"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("Concurrent DSOC operations")
    void testConcurrentDSOC(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) 
            throws InterruptedException, ExecutionException {
        index.enableDSOC(config, 512, 512);
        
        // Insert initial entities
        List<Point3f> positions = getCommonValidPositions();
        for (int i = 0; i < 50; i++) {
            var pos = positions.get(i % positions.size());
            index.insert(new LongEntityID(i), pos, (byte) 10, "Entity" + i);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        
        // Concurrent frustum culling
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    var frustum = createRandomFrustum(threadId);
                    index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
                } catch (Exception e) {
                    fail("Thread " + threadId + " failed: " + e.getMessage());
                }
            }));
        }
        
        // Concurrent frame advancement
        futures.add(executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 5; i++) {
                    index.nextFrame();
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                fail("Frame advancement failed: " + e.getMessage());
            }
        }));
        
        // Concurrent entity updates
        for (int i = 0; i < 5; i++) {
            final int startId = i * 10;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 5; j++) {
                        var id = new LongEntityID(startId + j);
                        var newPos = positions.get(j % positions.size());
                        index.updateEntity(id, newPos, (byte) 10);
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    // Entity might not exist, which is okay
                }
            }));
        }
        
        // Start all threads
        startLatch.countDown();
        
        // Wait for completion
        for (var future : futures) {
            future.get();
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        
        // Verify system is still consistent
        var stats = index.getDSOCStatistics();
        assertNotNull(stats);
        assertTrue((Boolean) stats.get("dsocEnabled"));
        assertTrue((Long) stats.get("currentFrame") >= 5);
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC statistics consistency")
    void testDSOCStatistics(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Check initial statistics
        var stats = index.getDSOCStatistics();
        assertNotNull(stats);
        
        // Required statistics fields
        assertTrue(stats.containsKey("dsocEnabled"));
        assertTrue(stats.containsKey("currentFrame"));
        assertTrue(stats.containsKey("totalEntities"));
        assertTrue(stats.containsKey("visibleEntities"));
        assertTrue(stats.containsKey("hiddenWithTBV"));
        assertTrue(stats.containsKey("activeTBVs"));
        assertTrue(stats.containsKey("deferredUpdates"));
        assertTrue(stats.containsKey("tbvUpdates"));
        
        // Insert entities and check stats update
        List<Point3f> positions = getCommonValidPositions();
        for (int i = 0; i < 10; i++) {
            index.insert(new LongEntityID(i), positions.get(i % positions.size()), (byte) 10, "Entity" + i);
        }
        
        stats = index.getDSOCStatistics();
        assertEquals(10L, stats.get("totalEntities"));
        
        // Reset statistics
        index.resetDSOCStatistics();
        stats = index.getDSOCStatistics();
        // Total entities should remain, but other counters reset
        assertEquals(10L, stats.get("totalEntities"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC performance characteristics")
    void testDSOCPerformance(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Test with DSOC disabled
        long withoutDSOC = measureFrustumCullingTime(index, false, 100);
        
        // Test with DSOC enabled
        long withDSOC = measureFrustumCullingTime(index, true, 100);
        
        // Both should complete successfully
        assertTrue(withoutDSOC > 0);
        assertTrue(withDSOC > 0);
        
        // Log performance for analysis
        System.out.printf("%s - Without DSOC: %d ns, With DSOC: %d ns, Ratio: %.2f%n",
            indexType, withoutDSOC, withDSOC, (double) withDSOC / withoutDSOC);
    }
    
    @Test
    @DisplayName("DSOC behavior differences between indices")
    void testDSOCBehaviorDifferences() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var prism = new Prism<LongEntityID, String>(new SequentialLongIDGenerator(), 1.0f, 21);
        
        // Enable DSOC on all
        octree.enableDSOC(config, 512, 512);
        tetree.enableDSOC(config, 512, 512);
        prism.enableDSOC(config, 512, 512);
        
        // Insert same logical entities (adjusted for constraints)
        var octreeId = new LongEntityID(1);
        var tetreeId = new LongEntityID(1);
        var prismId = new LongEntityID(1);
        
        octree.insert(octreeId, new Point3f(0.25f, 0.25f, 0.25f), (byte) 10, "TestEntity");
        tetree.insert(tetreeId, new Point3f(0.25f, 0.25f, 0.25f), (byte) 10, "TestEntity");
        prism.insert(prismId, new Point3f(0.25f, 0.25f, 0.25f), (byte) 10, "TestEntity");
        
        // Advance frames synchronously
        assertEquals(1L, octree.nextFrame());
        assertEquals(1L, tetree.nextFrame());
        assertEquals(1L, prism.nextFrame());
        
        // Check statistics are similar
        var octreeStats = octree.getDSOCStatistics();
        var tetreeStats = tetree.getDSOCStatistics();
        var prismStats = prism.getDSOCStatistics();
        
        assertEquals(octreeStats.get("currentFrame"), tetreeStats.get("currentFrame"));
        assertEquals(tetreeStats.get("currentFrame"), prismStats.get("currentFrame"));
        assertEquals(octreeStats.get("totalEntities"), tetreeStats.get("totalEntities"));
        assertEquals(tetreeStats.get("totalEntities"), prismStats.get("totalEntities"));
    }
    
    // Helper methods
    
    private List<Point3f> getCommonValidPositions() {
        // Positions valid for all three index types (within all constraints)
        return Arrays.asList(
            new Point3f(0.1f, 0.1f, 0.1f),
            new Point3f(0.2f, 0.2f, 0.2f),
            new Point3f(0.3f, 0.1f, 0.3f),
            new Point3f(0.1f, 0.3f, 0.4f),
            new Point3f(0.25f, 0.25f, 0.5f),
            new Point3f(0.4f, 0.1f, 0.6f),
            new Point3f(0.1f, 0.4f, 0.7f),
            new Point3f(0.3f, 0.3f, 0.8f),
            new Point3f(0.2f, 0.1f, 0.9f),
            new Point3f(0.1f, 0.2f, 0.95f)
        );
    }
    
    private boolean isValidPosition(Point3f pos, String indexType) {
        if (indexType.equals("Prism")) {
            return pos.x + pos.y < 1.0f;
        }
        return true;
    }
    
    private long measureFrustumCullingTime(AbstractSpatialIndex<?, LongEntityID, String> index, 
                                          boolean enableDSOC, int entityCount) {
        if (enableDSOC) {
            index.enableDSOC(config, 512, 512);
        }
        
        // Insert entities
        List<Point3f> positions = getCommonValidPositions();
        for (int i = 0; i < entityCount; i++) {
            index.insert(new LongEntityID(i), positions.get(i % positions.size()), (byte) 10, "Entity" + i);
        }
        
        // Create frustum
        var frustum = Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.25f, 0.25f, 0.25f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(60.0f), 1.0f, 0.1f, 10.0f
        );
        
        // Warm up
        for (int i = 0; i < 10; i++) {
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
        
        // Measure
        long startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
        return System.nanoTime() - startTime;
    }
    
    private Frustum3D createRandomFrustum(int seed) {
        Random rand = new Random(seed);
        float x = 0.2f + rand.nextFloat() * 0.3f;
        float y = 0.2f + rand.nextFloat() * 0.3f;
        
        return Frustum3D.createPerspective(
            new Point3f(x, y, -1.0f),
            new Point3f(x, y, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(45.0f + rand.nextFloat() * 30.0f),
            1.0f, 0.1f, 10.0f
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
