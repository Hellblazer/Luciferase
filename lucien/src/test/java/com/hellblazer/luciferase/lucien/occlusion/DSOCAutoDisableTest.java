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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DSOC functionality and performance characteristics
 *
 * @author hal.hildebrand
 */
public class DSOCAutoDisableTest {
    
    private DSOCConfiguration baseConfig;
    
    @BeforeEach
    void setUp() {
        baseConfig = DSOCConfiguration.defaultConfig()
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
    @DisplayName("DSOC performance monitoring")
    void testDSOCPerformanceMonitoring(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(baseConfig, 512, 512);
        assertTrue(index.isDSOCEnabled());
        
        // Create scenario with varying performance characteristics
        for (int i = 0; i < 5000; i++) {
            var pos = getScatteredPosition(i, indexType);
            index.insert(new LongEntityID(i), pos, (byte) 18, "Small" + i);
        }
        
        var frustum = createWideFrustum();
        
        // Monitor performance over multiple frames
        List<Long> frameTimes = new ArrayList<>();
        for (int frame = 0; frame < 50; frame++) {
            index.nextFrame();
            
            long start = System.nanoTime();
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
            long elapsed = System.nanoTime() - start;
            frameTimes.add(elapsed);
        }
        
        // Calculate average performance
        double avgTime = frameTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf("%s DSOC average frame time: %.2f ms%n", 
            indexType, avgTime / 1_000_000.0);
        
        // Verify DSOC remains operational
        var stats = index.getDSOCStatistics();
        assertTrue((Boolean) stats.get("dsocEnabled"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with different scene complexities")
    void testDSOCSceneComplexity(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(baseConfig, 512, 512);
        
        // Test with simple clustered scene
        for (int i = 0; i < 100; i++) {
            index.insert(new LongEntityID(i), getClusteredPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        var frustum = createNarrowFrustum();
        long simpleTime = measureAverageFrameTime(index, frustum, 20);
        
        // Add complexity
        for (int i = 100; i < 1000; i++) {
            index.insert(new LongEntityID(i), getValidPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        long complexTime = measureAverageFrameTime(index, frustum, 20);
        
        System.out.printf("%s Simple scene: %.2f ms, Complex scene: %.2f ms%n",
            indexType, simpleTime / 1_000_000.0, complexTime / 1_000_000.0);
        
        // DSOC should still be enabled
        assertTrue(index.isDSOCEnabled());
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC stability under stress")
    void testDSOCStability(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(baseConfig, 512, 512);
        
        // Create worst-case scenario
        createWorstCaseScenario(index, indexType);
        
        var frustum = createTestFrustum();
        
        // Stress test with many operations
        for (int i = 0; i < 100; i++) {
            index.nextFrame();
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
            
            // Update some entities
            if (i % 10 == 0) {
                for (int j = 0; j < 10; j++) {
                    var id = new LongEntityID(1000 + j); // Use IDs that exist
                    var pos = getValidPosition(i * 10 + j, indexType);
                    index.updateEntity(id, pos, (byte) 10);
                }
            }
        }
        
        // System should remain stable
        var stats = index.getDSOCStatistics();
        assertNotNull(stats);
        assertTrue((Long) stats.get("currentFrame") >= 100);
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC concurrent operation stability")
    void testDSOCConcurrentStability(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) 
            throws InterruptedException {
        index.enableDSOC(baseConfig, 512, 512);
        
        // Insert initial entities
        for (int i = 0; i < 1000; i++) {
            index.insert(new LongEntityID(i), getValidPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(4);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Concurrent operations
        for (int t = 0; t < 4; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    switch (threadId) {
                        case 0: // Frustum culling
                            for (int i = 0; i < 50; i++) {
                                var frustum = createRandomFrustum(i);
                                index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
                                Thread.sleep(10);
                            }
                            break;
                            
                        case 1: // Entity updates
                            Random rand = new Random();
                            for (int i = 0; i < 200; i++) {
                                var id = new LongEntityID(rand.nextInt(1000));
                                var pos = getValidPosition(rand.nextInt(10000), indexType);
                                index.updateEntity(id, pos, (byte) 10);
                                Thread.sleep(5);
                            }
                            break;
                            
                        case 2: // Frame advancement
                            for (int i = 0; i < 100; i++) {
                                index.nextFrame();
                                Thread.sleep(10);
                            }
                            break;
                            
                        case 3: // Statistics queries
                            for (int i = 0; i < 200; i++) {
                                var stats = index.getDSOCStatistics();
                                assertNotNull(stats);
                                Thread.sleep(5);
                            }
                            break;
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        
        assertEquals(0, errorCount.get(), "Concurrent operations should not cause errors");
        
        // DSOC should remain functional
        var finalStats = index.getDSOCStatistics();
        assertNotNull(finalStats);
        assertTrue((Boolean) finalStats.get("dsocEnabled"));
    }
    
    @Test
    @DisplayName("DSOC configuration handling")
    void testDSOCConfiguration() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        // Test various configurations
        var config1 = baseConfig.withTBVRefreshThreshold(0.1f);
        assertDoesNotThrow(() -> octree.enableDSOC(config1, 512, 512));
        assertTrue(octree.isDSOCEnabled());
        
        // Re-enable with different config
        var config2 = baseConfig.withMinOccluderVolume(50.0f);
        assertDoesNotThrow(() -> octree.enableDSOC(config2, 256, 256));
        assertTrue(octree.isDSOCEnabled());
        
        // Test with edge case values
        var config3 = baseConfig
            .withTBVRefreshThreshold(0.0f)
            .withMinOccluderVolume(0.0f);
        assertDoesNotThrow(() -> octree.enableDSOC(config3, 1024, 1024));
        assertTrue(octree.isDSOCEnabled());
    }
    
    @Test
    @DisplayName("DSOC state preservation")
    void testDSOCStatePreservation() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        octree.enableDSOC(baseConfig, 512, 512);
        
        // Insert entities and advance frames
        for (int i = 0; i < 100; i++) {
            octree.insert(new LongEntityID(i), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), (byte) 10, "Entity" + i);
        }
        
        long initialFrame = octree.getCurrentFrame();
        
        // Perform operations
        var frustum = createTestFrustum();
        for (int i = 0; i < 20; i++) {
            octree.nextFrame();
            octree.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
        
        // Verify state is preserved
        assertEquals(initialFrame + 20, octree.getCurrentFrame());
        
        var stats = octree.getDSOCStatistics();
        assertNotNull(stats);
        assertEquals(100L, stats.get("totalEntities"));
        assertEquals(initialFrame + 20, stats.get("currentFrame"));
        assertTrue((Boolean) stats.get("dsocEnabled"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC with varying entity densities")
    void testDSOCEntityDensity(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(baseConfig, 512, 512);
        
        // Test performance with different entity densities
        for (int phase = 0; phase < 3; phase++) {
            int density = (int) Math.pow(10, phase + 2); // 100, 1000, 10000
            
            // Clear previous entities
            for (int i = 0; i < 10000; i++) {
                index.removeEntity(new LongEntityID(i));
            }
            
            // Add entities with current density
            for (int i = 0; i < density; i++) {
                var id = new LongEntityID(i);
                var pos = getValidPosition(i, indexType);
                index.insert(id, pos, (byte) (10 + phase), "Density" + phase + "_" + i);
            }
            
            // Measure performance
            var frustum = createTestFrustum();
            long avgTime = measureAverageFrameTime(index, frustum, 10);
            
            var stats = index.getDSOCStatistics();
            System.out.printf("%s Density %d - Time: %.2f ms, Entities: %d%n", 
                indexType, density, avgTime / 1_000_000.0, stats.get("totalEntities"));
        }
    }
    
    // Helper methods
    
    private long measureAverageFrameTime(AbstractSpatialIndex<?, LongEntityID, String> index, 
                                        Frustum3D frustum, int frames) {
        long totalTime = 0;
        
        for (int i = 0; i < frames; i++) {
            index.nextFrame();
            
            long start = System.nanoTime();
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
            totalTime += System.nanoTime() - start;
        }
        
        return totalTime / frames;
    }
    
    private Point3f getValidPosition(int seed, String indexType) {
        Random rand = new Random(seed);
        
        if (indexType.equals("Prism")) {
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
    
    private Point3f getScatteredPosition(int index, String indexType) {
        Random rand = new Random(index * 7);
        
        float x = rand.nextFloat() * 0.99f;
        float y = rand.nextFloat() * 0.99f;
        float z = rand.nextFloat() * 0.99f;
        
        if (indexType.equals("Prism") && x + y >= 1.0f) {
            float scale = 0.95f / (x + y);
            x *= scale;
            y *= scale;
        }
        
        return new Point3f(x, y, z);
    }
    
    private Point3f getClusteredPosition(int index, String indexType) {
        int cluster = index / 20;
        float baseX = (cluster % 5) * 0.15f + 0.1f;
        float baseY = ((cluster / 5) % 5) * 0.15f + 0.1f;
        float baseZ = ((cluster / 25) % 4) * 0.2f + 0.1f;
        
        Random rand = new Random(index);
        float x = baseX + rand.nextFloat() * 0.05f;
        float y = baseY + rand.nextFloat() * 0.05f;
        float z = baseZ + rand.nextFloat() * 0.05f;
        
        if (indexType.equals("Prism") && x + y >= 1.0f) {
            float scale = 0.9f / (x + y);
            x *= scale;
            y *= scale;
        }
        
        return new Point3f(x, y, z);
    }
    
    private void createWorstCaseScenario(AbstractSpatialIndex<?, LongEntityID, String> index, String indexType) {
        for (int i = 1000; i < 5000; i++) {
            var pos = getScatteredPosition(i, indexType);
            index.insert(new LongEntityID(i), pos, (byte) 18, "Worst" + i);
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
    
    private Frustum3D createWideFrustum() {
        return Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(120.0f), 1.0f, 0.1f, 100.0f
        );
    }
    
    private Frustum3D createNarrowFrustum() {
        return Frustum3D.createPerspective(
            new Point3f(0.5f, 0.5f, -1),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(20.0f), 1.0f, 0.1f, 10.0f
        );
    }
    
    private Frustum3D createRandomFrustum(int seed) {
        Random rand = new Random(seed);
        return Frustum3D.createPerspective(
            new Point3f(rand.nextFloat(), rand.nextFloat(), -1 - rand.nextFloat()),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(30.0f + rand.nextFloat() * 60.0f),
            1.0f, 0.1f, 10.0f
        );
    }
}
