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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory leak and stress tests for DSOC functionality
 *
 * @author hal.hildebrand
 */
@EnabledIfSystemProperty(named = "test.stress", matches = "true")
public class DSOCMemoryStressTest {
    
    private DSOCConfiguration config;
    private MemoryMXBean memoryBean;
    
    @BeforeEach
    void setUp() {
        config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withAutoDynamicsEnabled(true)
            .withEnableHierarchicalOcclusion(true);
        
        memoryBean = ManagementFactory.getMemoryMXBean();
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
    @DisplayName("DSOC memory leak detection - entity lifecycle")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMemoryLeakEntityLifecycle(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 512, 512);
        
        // Force GC and get baseline
        forceGC();
        long baselineMemory = getUsedMemory();
        
        // Perform many entity lifecycle operations
        for (int cycle = 0; cycle < 100; cycle++) {
            // Insert batch
            List<LongEntityID> ids = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                var id = new LongEntityID(cycle * 1000 + i);
                ids.add(id);
                index.insert(id, getValidPosition(i, indexType), (byte) 10, "Entity" + i);
            }
            
            // Update all
            for (int i = 0; i < ids.size(); i++) {
                index.updateEntity(ids.get(i), getValidPosition(i + 1000, indexType), (byte) 10);
            }
            
            // Frustum cull to update visibility
            var frustum = createTestFrustum();
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
            
            // Remove all
            for (var id : ids) {
                index.removeEntity(id);
            }
            
            // Advance frame
            index.nextFrame();
            
            // Check memory periodically
            if (cycle % 10 == 0) {
                forceGC();
                long currentMemory = getUsedMemory();
                long memoryGrowth = currentMemory - baselineMemory;
                
                // Allow some growth but flag potential leak
                assertTrue(memoryGrowth < 50 * 1024 * 1024, // 50MB threshold
                    String.format("Potential memory leak detected: %d MB growth after %d cycles",
                        memoryGrowth / (1024 * 1024), cycle));
            }
        }
        
        // Final memory check
        forceGC();
        long finalMemory = getUsedMemory();
        long totalGrowth = finalMemory - baselineMemory;
        
        System.out.printf("%s memory growth: %d KB%n", indexType, totalGrowth / 1024);
        assertTrue(totalGrowth < 10 * 1024 * 1024, // 10MB final threshold
            "Excessive memory growth indicates potential leak");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC memory leak detection - frame advancement")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMemoryLeakFrameAdvancement(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        index.enableDSOC(config, 256, 256); // Smaller Z-buffer
        
        // Insert static entities
        for (int i = 0; i < 500; i++) {
            index.insert(new LongEntityID(i), getValidPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        forceGC();
        long baselineMemory = getUsedMemory();
        
        // Advance many frames without other operations
        for (int frame = 0; frame < 10000; frame++) {
            index.nextFrame();
            
            // Periodically perform culling
            if (frame % 100 == 0) {
                var frustum = createRandomFrustum(frame);
                index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
            }
            
            // Check memory
            if (frame % 1000 == 0 && frame > 0) {
                forceGC();
                long currentMemory = getUsedMemory();
                long memoryGrowth = currentMemory - baselineMemory;
                
                assertTrue(memoryGrowth < 20 * 1024 * 1024, // 20MB threshold
                    String.format("Memory leak in frame advancement: %d MB after %d frames",
                        memoryGrowth / (1024 * 1024), frame));
            }
        }
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC memory stress - maximum entities")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testMemoryStressMaximumEntities(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Configure with minimal Z-buffer
        index.enableDSOC(config, 128, 128); // Minimal Z-buffer
        
        // Insert as many entities as possible
        int batchSize = 10000;
        int totalInserted = 0;
        
        try {
            for (int batch = 0; batch < 100; batch++) {
                for (int i = 0; i < batchSize; i++) {
                    var id = new LongEntityID(batch * batchSize + i);
                    index.insert(id, getValidPosition(i, indexType), (byte) 15, "E" + i);
                    totalInserted++;
                }
                
                // Perform operations to stress DSOC
                if (batch % 5 == 0) {
                    index.nextFrame();
                    var frustum = createTestFrustum();
                    index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
                }
                
                // Check if we're running low on memory
                MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
                double usedRatio = (double) heapUsage.getUsed() / heapUsage.getMax();
                
                if (usedRatio > 0.9) {
                    System.out.printf("%s reached memory limit at %d entities%n", 
                        indexType, totalInserted);
                    break;
                }
            }
        } catch (OutOfMemoryError e) {
            System.out.printf("%s OOM at %d entities%n", indexType, totalInserted);
        }
        
        // System should still be functional
        var stats = index.getDSOCStatistics();
        assertNotNull(stats);
        assertEquals((long) totalInserted, stats.get("totalEntities"));
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC memory stress - rapid configuration changes")
    void testMemoryStressConfigurationChanges(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) {
        // Insert entities
        for (int i = 0; i < 1000; i++) {
            index.insert(new LongEntityID(i), getValidPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        forceGC();
        long baselineMemory = getUsedMemory();
        
        // Rapidly change configurations
        for (int i = 0; i < 100; i++) {
            // Vary Z-buffer size
            int zBufferSize = 64 + (i % 10) * 64; // 64 to 640
            
            // Vary Z-buffer size
            index.enableDSOC(config, zBufferSize, zBufferSize);
            
            // Perform operations
            index.nextFrame();
            var frustum = createRandomFrustum(i);
            index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
        }
        
        forceGC();
        long finalMemory = getUsedMemory();
        long memoryGrowth = finalMemory - baselineMemory;
        
        assertTrue(memoryGrowth < 50 * 1024 * 1024, // 50MB threshold
            "Excessive memory growth from configuration changes");
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("spatialIndexProvider")
    @DisplayName("DSOC concurrent memory stress")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testConcurrentMemoryStress(String indexType, AbstractSpatialIndex<?, LongEntityID, String> index) 
            throws InterruptedException {
        index.enableDSOC(config, 256, 256);
        
        // Insert initial entities
        int entityCount = 5000;
        for (int i = 0; i < entityCount; i++) {
            index.insert(new LongEntityID(i), getValidPosition(i, indexType), (byte) 10, "Entity" + i);
        }
        
        forceGC();
        long baselineMemory = getUsedMemory();
        
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(8);
        AtomicBoolean memoryOk = new AtomicBoolean(true);
        
        // Thread 1-2: Continuous insertion/removal
        for (int t = 0; t < 2; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 1000; i++) {
                        var id = new LongEntityID(10000 + threadId * 1000 + i);
                        var pos = getValidPosition(i, indexType);
                        index.insert(id, pos, (byte) 10, "Thread" + threadId + "_" + i);
                        
                        if (i > 100) {
                            // Remove old entity
                            index.removeEntity(new LongEntityID(10000 + threadId * 1000 + i - 100));
                        }
                        
                        if (i % 100 == 0) {
                            checkMemoryGrowth(baselineMemory, 100, memoryOk);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Thread 3-4: Continuous updates
        for (int t = 0; t < 2; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < 5000; i++) {
                        var id = new LongEntityID(rand.nextInt(entityCount));
                        var pos = getValidPosition(rand.nextInt(10000), indexType);
                        index.updateEntity(id, pos, (byte) 10);
                        
                        if (i % 500 == 0) {
                            checkMemoryGrowth(baselineMemory, 100, memoryOk);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Thread 5-6: Continuous frustum culling
        for (int t = 0; t < 2; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 2000; i++) {
                        var frustum = createRandomFrustum(threadId * 1000 + i);
                        index.frustumCullVisible(frustum, new Point3f(0.5f, 0.5f, -1));
                        Thread.sleep(2);
                        
                        if (i % 200 == 0) {
                            checkMemoryGrowth(baselineMemory, 100, memoryOk);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Thread 7: Frame advancement
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 1000; i++) {
                    index.nextFrame();
                    Thread.sleep(5);
                    
                    if (i % 100 == 0) {
                        checkMemoryGrowth(baselineMemory, 100, memoryOk);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });
        
        // Thread 8: Statistics queries
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 5000; i++) {
                    var stats = index.getDSOCStatistics();
                    assertNotNull(stats);
                    index.resetDSOCStatistics();
                    
                    if (i % 500 == 0) {
                        checkMemoryGrowth(baselineMemory, 100, memoryOk);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });
        
        // Start stress test
        startLatch.countDown();
        
        // Wait for completion
        assertTrue(doneLatch.await(120, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Check final memory state
        assertTrue(memoryOk.get(), "Memory growth exceeded threshold during concurrent stress");
        
        forceGC();
        long finalMemory = getUsedMemory();
        long totalGrowth = finalMemory - baselineMemory;
        
        System.out.printf("%s concurrent stress memory growth: %d MB%n", 
            indexType, totalGrowth / (1024 * 1024));
        
        assertTrue(totalGrowth < 200 * 1024 * 1024, // 200MB threshold for concurrent stress
            "Excessive memory growth in concurrent stress test");
    }
    
    @Test
    @DisplayName("DSOC Z-buffer memory scaling")
    void testZBufferMemoryScaling() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        // Insert entities
        for (int i = 0; i < 1000; i++) {
            octree.insert(new LongEntityID(i), new Point3f(i * 0.001f, i * 0.001f, i * 0.001f), (byte) 10, "Entity" + i);
        }
        
        // Test different Z-buffer sizes
        int[] sizes = {64, 128, 256, 512, 1024, 2048};
        long[] memories = new long[sizes.length];
        
        for (int i = 0; i < sizes.length; i++) {
            forceGC();
            long before = getUsedMemory();
            
            octree.enableDSOC(config, sizes[i], sizes[i]);
            
            // Perform operations to allocate Z-buffer
            octree.updateCamera(createIdentityMatrix(), createIdentityMatrix(), new Point3f(0, 0, -1));
            var frustum = createTestFrustum();
            octree.frustumCullVisible(frustum, new Point3f(0, 0, -1));
            
            forceGC();
            memories[i] = getUsedMemory() - before;
            
            System.out.printf("Z-buffer %dx%d memory: %d KB%n", 
                sizes[i], sizes[i], memories[i] / 1024);
            
            // Note: No disableDSOC method available
        }
        
        // Memory should scale quadratically with size
        for (int i = 1; i < sizes.length; i++) {
            double expectedRatio = Math.pow((double) sizes[i] / sizes[i-1], 2);
            double actualRatio = (double) memories[i] / memories[i-1];
            
            // Allow some variance due to overhead
            assertTrue(actualRatio < expectedRatio * 2.0,
                "Z-buffer memory scaling is excessive");
        }
    }
    
    // Helper methods
    
    private void forceGC() {
        System.gc();
        System.runFinalization();
        System.gc();
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private long getUsedMemory() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return heapUsage.getUsed();
    }
    
    private void checkMemoryGrowth(long baseline, long thresholdMB, AtomicBoolean memoryOk) {
        long current = getUsedMemory();
        long growth = current - baseline;
        
        if (growth > thresholdMB * 1024 * 1024) {
            memoryOk.set(false);
        }
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
            new Point3f(rand.nextFloat(), rand.nextFloat(), -1 - rand.nextFloat()),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0, 1, 0),
            (float)Math.toRadians(30.0f + rand.nextFloat() * 60.0f),
            1.0f, 0.1f, 10.0f
        );
    }
    
    private float[] createIdentityMatrix() {
        float[] matrix = new float[16];
        for (int i = 0; i < 16; i++) {
            matrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
        return matrix;
    }
}
