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
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Plane3D;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case and error handling tests for DSOC functionality
 *
 * @author hal.hildebrand
 */
public class DSOCEdgeCaseTest {
    
    private DSOCConfiguration config;
    
    @BeforeEach
    void setUp() {
        config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withAutoDynamicsEnabled(true)
            .withEnableHierarchicalOcclusion(true);
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
    @DisplayName("DSOC with null and invalid inputs")
    void testDSOCWithInvalidInputs(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Test null frustum
        assertThrows(NullPointerException.class, () -> 
            index.frustumCullVisible(null, new Point3f(0, 0, 0))
        );
        
        // Test null camera position
        var frustum = createTestFrustum();
        assertDoesNotThrow(() -> 
            index.frustumCullVisible(frustum, null)
        );
        
        // Test null camera matrices
        assertThrows(NullPointerException.class, () -> 
            index.updateCamera(null, new float[16], new Point3f(0, 0, 0))
        );
        
        assertThrows(NullPointerException.class, () -> 
            index.updateCamera(new float[16], null, new Point3f(0, 0, 0))
        );
        
        // Test invalid matrix sizes
        assertThrows(IllegalArgumentException.class, () -> 
            index.updateCamera(new float[15], new float[16], new Point3f(0, 0, 0))
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            index.updateCamera(new float[16], new float[15], new Point3f(0, 0, 0))
        );
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with extreme entity counts")
    void testDSOCWithExtremeEntityCounts(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 256, 256); // Smaller Z-buffer for memory
        
        // Test with zero entities
        var frustum = createTestFrustum();
        var results = index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        assertNotNull(results);
        assertTrue(results.isEmpty());
        
        // Test with single entity
        var singleId = new LongEntityID(1);
        index.insert(singleId, new Point3f(0.1f, 0.1f, 0.1f), (byte) 10, "SingleEntity");
        results = index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        assertNotNull(results);
        
        // Test with many entities (memory pressure)
        int largeCount = 10000;
        List<LongEntityID> ids = new ArrayList<>();
        for (int i = 2; i <= largeCount; i++) {
            var id = new LongEntityID(i);
            ids.add(id);
            var pos = getValidPosition(i, indexType);
            if (pos != null) {
                index.insert(id, pos, (byte) 10, "Entity" + i);
            }
        }
        
        // Should handle large frustum culling
        index.nextFrame();
        results = index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        assertNotNull(results);
        
        // Mass removal
        for (var id : ids) {
            index.removeEntity(id);
        }
        
        var stats = index.getDSOCStatistics();
        assertEquals(1L, stats.get("totalEntities")); // Only single entity remains
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with rapid configuration changes")
    void testDSOCRapidConfigChanges(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Enable/disable rapidly
        for (int i = 0; i < 10; i++) {
            index.enableDSOC(config, 512, 512);
            assertTrue(index.isDSOCEnabled());
            
            // Insert entity while enabled
            var id = new LongEntityID(i);
            index.insert(id, getValidPosition(i, indexType), (byte) 10, "Entity" + i);
            
            // Note: No disableDSOC method available
        }
        
        // Re-enable and verify entities still work
        index.enableDSOC(config, 512, 512);
        var frustum = createTestFrustum();
        var results = index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        assertNotNull(results);
        
        // Change Z-buffer size while running
        index.enableDSOC(config, 1024, 1024);
        assertTrue(index.isDSOCEnabled());
        
        index.enableDSOC(config, 128, 128);
        assertTrue(index.isDSOCEnabled());
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with entity lifecycle edge cases")
    void testDSOCEntityLifecycleEdgeCases(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Test 1: Update non-existent entity
        var nonExistentId = new LongEntityID(999);
        assertThrows(IllegalArgumentException.class, () -> 
            index.updateEntity(nonExistentId, new Point3f(0.1f, 0.1f, 0.1f), (byte) 10)
        );
        
        // Test 2: Remove already removed entity
        var id = new LongEntityID(1);
        index.insert(id, getValidPosition(1, indexType), (byte) 10, "TestEntity");
        assertTrue(index.removeEntity(id));
        assertFalse(index.removeEntity(id)); // Second removal should return false
        
        // Test 3: Insert with same ID multiple times
        index.insert(id, getValidPosition(1, indexType), (byte) 10, "TestEntity");
        assertDoesNotThrow(() -> 
            index.insert(id, getValidPosition(2, indexType), (byte) 10, "TestEntity2")
        );
        
        // Test 4: Rapid insert/remove cycles
        for (int i = 0; i < 100; i++) {
            var cycleId = new LongEntityID(100 + i);
            index.insert(cycleId, getValidPosition(i, indexType), (byte) 10, "CycleEntity");
            index.nextFrame();
            index.removeEntity(cycleId);
        }
        
        // Test 5: Update entity to invalid position
        if (indexType.equals("Prism")) {
            var prismId = new LongEntityID(200);
            index.insert(prismId, new Point3f(0.1f, 0.1f, 0.1f), (byte) 10, "PrismEntity");
            
            // Try to update to invalid position (x+y >= 1)
            assertThrows(IllegalArgumentException.class, () -> 
                index.updateEntity(prismId, new Point3f(0.6f, 0.6f, 0.1f), (byte) 10)
            );
        }
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with extreme camera positions")
    void testDSOCWithExtremeCameraPositions(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Insert entities
        for (int i = 0; i < 10; i++) {
            index.insert(new LongEntityID(i), getValidPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        // Test very far camera
        var farCamera = new Point3f(1000, 1000, 1000);
        final var farViewMatrix = createViewMatrix(farCamera, new Point3f(0.5f, 0.5f, 0.5f), new Vector3f(0, 1, 0));
        final var projMatrix = createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 10000.0f);
        assertDoesNotThrow(() -> index.updateCamera(farViewMatrix, projMatrix, farCamera));
        
        // Test very close camera
        var closeCamera = new Point3f(0.01f, 0.01f, 0.01f);
        final var closeViewMatrix = createViewMatrix(closeCamera, new Point3f(0.5f, 0.5f, 0.5f), new Vector3f(0, 1, 0));
        assertDoesNotThrow(() -> index.updateCamera(closeViewMatrix, projMatrix, closeCamera));
        
        // Test camera inside world bounds
        var insideCamera = new Point3f(0.5f, 0.5f, 0.5f);
        final var insideViewMatrix = createViewMatrix(insideCamera, new Point3f(0.25f, 0.25f, 0.25f), new Vector3f(0, 1, 0));
        assertDoesNotThrow(() -> index.updateCamera(insideViewMatrix, projMatrix, insideCamera));
        
        // Test negative camera positions
        var negativeCamera = new Point3f(-10, -10, -10);
        final var negativeViewMatrix = createViewMatrix(negativeCamera, new Point3f(0.5f, 0.5f, 0.5f), new Vector3f(0, 1, 0));
        assertDoesNotThrow(() -> index.updateCamera(negativeViewMatrix, projMatrix, negativeCamera));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with degenerate frustums")
    void testDSOCWithDegenerateFrustums(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Insert entities
        for (int i = 0; i < 5; i++) {
            index.insert(new LongEntityID(i), getValidPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        // Test 1: Very narrow frustum (almost a line)
        var narrowFrustum = Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(0.1f), // Very narrow FOV
            1.0f, 0.1f, 10.0f
        );
        assertDoesNotThrow(() -> 
            index.frustumCullVisible(narrowFrustum, new Point3f(0.5f, 0.5f, -1))
        );
        
        // Test 2: Very wide frustum (almost 180 degrees)
        var wideFrustum = Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(179.0f), // Very wide FOV
            1.0f, 0.1f, 10.0f
        );
        assertDoesNotThrow(() -> 
            index.frustumCullVisible(wideFrustum, new Point3f(0.5f, 0.5f, -1))
        );
        
        // Test 3: Zero volume frustum (near == far) should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createOrthographic(
                new Point3f(0.5f, 0.5f, -1),
                new Point3f(0.5f, 0.5f, 0.5f),
                new Vector3f(0, 1, 0),
                0.4f, 0.6f, 0.4f, 0.6f,
                1.0f, 1.0f // near == far - invalid!
            );
        });
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with concurrent modification stress")
    void testDSOCConcurrentModificationStress(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) 
            throws InterruptedException {
        index.enableDSOC(config, 512, 512);
        
        // Insert initial entities
        int entityCount = 100;
        for (int i = 0; i < entityCount; i++) {
            var pos = getValidPosition(i, indexType);
            if (pos != null) {
                index.insert(new LongEntityID(i), pos, (byte) 10, "Entity" + i);
            }
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Concurrent operations
        List<Future<?>> futures = new ArrayList<>();
        
        // Thread 1-2: Continuous frustum culling
        for (int t = 0; t < 2; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 100; i++) {
                        var frustum = createRandomFrustum(threadId * 100 + i);
                        index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            }));
        }
        
        // Thread 3-4: Entity updates
        for (int t = 0; t < 2; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < 100; i++) {
                        var id = new LongEntityID(rand.nextInt(entityCount));
                        var newPos = getValidPosition(rand.nextInt(1000), indexType);
                        if (newPos != null) {
                            index.updateEntity(id, newPos, (byte) 10);
                        }
                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            }));
        }
        
        // Thread 5: Frame advancement
        futures.add(executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 50; i++) {
                    index.nextFrame();
                    Thread.sleep(5);
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
            }
        }));
        
        // Thread 6: Entity insertion/removal
        futures.add(executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 50; i++) {
                    var id = new LongEntityID(1000 + i);
                    var pos = getValidPosition(i, indexType);
                    if (pos != null) {
                        index.insert(id, pos, (byte) 10, "NewEntity" + i);
                        Thread.sleep(2);
                        index.removeEntity(id);
                    }
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
            }
        }));
        
        // Thread 7: Statistics queries
        futures.add(executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 100; i++) {
                    var stats = index.getDSOCStatistics();
                    assertNotNull(stats);
                    Thread.sleep(2);
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
            }
        }));
        
        // Thread 8: Camera updates
        futures.add(executor.submit(() -> {
            try {
                startLatch.await();
                Random rand = new Random();
                for (int i = 0; i < 50; i++) {
                    var cam = new Point3f(
                        rand.nextFloat() * 2 - 1,
                        rand.nextFloat() * 2 - 1,
                        -1 - rand.nextFloat() * 5
                    );
                    var view = createViewMatrix(cam, new Point3f(0.5f, 0.5f, 0.5f), new Vector3f(0, 1, 0));
                    var proj = createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 100.0f);
                    index.updateCamera(view, proj, cam);
                    Thread.sleep(3);
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
            }
        }));
        
        // Start all threads
        startLatch.countDown();
        
        // Wait for completion
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        
        // Check for errors
        assertEquals(0, errorCount.get(), "Concurrent operations caused errors");
        
        // Verify system still functional
        var finalStats = index.getDSOCStatistics();
        assertNotNull(finalStats);
        assertTrue((Boolean) finalStats.get("dsocEnabled"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with memory pressure scenarios")
    void testDSOCMemoryPressure(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Configure with small Z-buffer for memory constraints
        index.enableDSOC(config, 128, 128); // Small Z-buffer
        
        // Insert many entities to create memory pressure
        int count = 0;
        for (int i = 0; i < 1000; i++) {
            var pos = getValidPosition(i, indexType);
            if (pos != null) {
                index.insert(new LongEntityID(i), pos, (byte) 10, "Entity" + i);
                count++;
            }
        }
        
        // Perform many operations to stress memory
        for (int frame = 0; frame < 20; frame++) {
            index.nextFrame();
            
            // Update many entities
            for (int i = frame * 50; i < (frame + 1) * 50 && i < count; i++) {
                var newPos = getValidPosition(i + 1000, indexType);
                if (newPos != null) {
                    index.updateEntity(new LongEntityID(i), newPos, (byte) 10);
                }
            }
            
            // Perform frustum culling
            var frustum = createRandomFrustum(frame);
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
        
        // System should still be functional
        var stats = index.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
        
        // Reset statistics should work under memory pressure
        assertDoesNotThrow(() -> index.resetDSOCStatistics());
    }
    
    @Test
    @DisplayName("DSOC recovery from error conditions")
    void testDSOCErrorRecovery() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        // Test recovery from invalid configuration
        assertThrows(IllegalArgumentException.class, () -> {
            new DSOCConfiguration()
                .withEnabled(true)
                .withTBVRefreshThreshold(-0.5f);   // Invalid (should be 0-1)
        });
        
        // Create a valid config instead
        var validConfig = new DSOCConfiguration()
            .withEnabled(true)
            .withTBVRefreshThreshold(0.5f)
            .withMinOccluderVolume(0.001f);
        
        // Should handle valid config gracefully
        assertDoesNotThrow(() -> octree.enableDSOC(validConfig, 512, 512));
        
        // Should still be functional with defaults
        assertTrue(octree.isDSOCEnabled());
        
        // Insert entities and verify functionality
        for (int i = 0; i < 10; i++) {
            octree.insert(new LongEntityID(i), new Point3f(i * 0.1f, i * 0.1f, i * 0.1f), (byte) 10, "Entity" + i);
        }
        
        var frustum = createTestFrustum();
        var results = octree.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        assertNotNull(results);
        
        // Should recover from rapid re-enable
        for (int i = 0; i < 10; i++) {
            octree.enableDSOC(config, 512, 512);
        }
        
        // Still functional
        results = octree.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        assertNotNull(results);
    }
    
    // Helper methods
    
    private Point3f getValidPosition(int seed, String indexType) {
        Random rand = new Random(seed);
        
        if (indexType.equals("Prism")) {
            // Ensure x + y < 1 for Prism
            float x = rand.nextFloat() * 0.4f;
            float y = rand.nextFloat() * 0.4f;
            float z = rand.nextFloat();
            return new Point3f(x, y, z);
        } else {
            // Octree and Tetree accept any positive coordinates
            return new Point3f(
                rand.nextFloat() * 0.9f,
                rand.nextFloat() * 0.9f,
                rand.nextFloat() * 0.9f
            );
        }
    }
    
    private Frustum3D createTestFrustum() {
        return Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(60.0f), 1.0f, 0.1f, 10.0f
        );
    }
    
    private Frustum3D createRandomFrustum(int seed) {
        Random rand = new Random(seed);
        return Frustum3D.createPerspective(
            new Point3f(rand.nextFloat(), rand.nextFloat(), -1 - rand.nextFloat() * 2),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(30.0f + rand.nextFloat() * 60.0f),
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
