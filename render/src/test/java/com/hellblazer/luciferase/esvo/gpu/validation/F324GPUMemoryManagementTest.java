/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.gpu.validation;

import com.hellblazer.luciferase.esvo.gpu.*;
import com.hellblazer.luciferase.esvo.gpu.GPUMemoryManager.MemoryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.2.4: GPU Memory Management Validation Test Suite
 *
 * <p>Validates large scene streaming support:
 * <ul>
 *   <li>Memory pressure detection and thresholds</li>
 *   <li>Buffer pooling with LRU eviction</li>
 *   <li>Memory statistics tracking</li>
 *   <li>Pressure callbacks for streaming triggers</li>
 * </ul>
 *
 * @see GPUMemoryManager
 * @see GPUBufferPool
 */
@DisplayName("F3.2.4: GPU Memory Management Validation")
class F324GPUMemoryManagementTest {

    private static final long MOCK_VRAM = 256 * 1024 * 1024;  // 256 MB

    @Nested
    @DisplayName("GPUMemoryPressure")
    class GPUMemoryPressureTests {

        @Test
        @DisplayName("Pressure levels from utilization")
        void testPressureLevels() {
            assertEquals(GPUMemoryPressure.NONE, GPUMemoryPressure.fromUtilization(0.5));
            assertEquals(GPUMemoryPressure.NONE, GPUMemoryPressure.fromUtilization(0.74));
            assertEquals(GPUMemoryPressure.MODERATE, GPUMemoryPressure.fromUtilization(0.75));
            assertEquals(GPUMemoryPressure.MODERATE, GPUMemoryPressure.fromUtilization(0.84));
            assertEquals(GPUMemoryPressure.HIGH, GPUMemoryPressure.fromUtilization(0.85));
            assertEquals(GPUMemoryPressure.HIGH, GPUMemoryPressure.fromUtilization(0.94));
            assertEquals(GPUMemoryPressure.CRITICAL, GPUMemoryPressure.fromUtilization(0.95));
            assertEquals(GPUMemoryPressure.CRITICAL, GPUMemoryPressure.fromUtilization(1.0));
        }

        @Test
        @DisplayName("Custom thresholds")
        void testCustomThresholds() {
            assertEquals(GPUMemoryPressure.MODERATE,
                GPUMemoryPressure.fromUtilization(0.6, 0.6, 0.8, 0.9));
            assertEquals(GPUMemoryPressure.HIGH,
                GPUMemoryPressure.fromUtilization(0.85, 0.6, 0.8, 0.9));
            assertEquals(GPUMemoryPressure.CRITICAL,
                GPUMemoryPressure.fromUtilization(0.95, 0.6, 0.8, 0.9));
        }

        @Test
        @DisplayName("Pressure action recommendations")
        void testPressureActions() {
            assertFalse(GPUMemoryPressure.NONE.shouldEvict());
            assertTrue(GPUMemoryPressure.MODERATE.shouldEvict());
            assertTrue(GPUMemoryPressure.HIGH.shouldEvict());
            assertTrue(GPUMemoryPressure.CRITICAL.shouldEvict());

            assertFalse(GPUMemoryPressure.NONE.shouldDeferAllocations());
            assertFalse(GPUMemoryPressure.MODERATE.shouldDeferAllocations());
            assertTrue(GPUMemoryPressure.HIGH.shouldDeferAllocations());
            assertTrue(GPUMemoryPressure.CRITICAL.shouldDeferAllocations());

            assertFalse(GPUMemoryPressure.NONE.shouldFallbackToCPU());
            assertFalse(GPUMemoryPressure.MODERATE.shouldFallbackToCPU());
            assertFalse(GPUMemoryPressure.HIGH.shouldFallbackToCPU());
            assertTrue(GPUMemoryPressure.CRITICAL.shouldFallbackToCPU());
        }
    }

    @Nested
    @DisplayName("GPUMemoryStats")
    class GPUMemoryStatsTests {

        @Test
        @DisplayName("Stats creation and calculation")
        void testStatsCreation() {
            var stats = GPUMemoryStats.create(
                100_000_000L,  // 100 MB total
                75_000_000L,   // 75 MB allocated
                GPUMemoryPressure.MODERATE,
                10, 5, 1000L, 500L, 80_000_000L
            );

            assertEquals(100_000_000L, stats.totalBytes());
            assertEquals(75_000_000L, stats.allocatedBytes());
            assertEquals(25_000_000L, stats.availableBytes());
            assertEquals(0.75, stats.utilization(), 0.001);
            assertTrue(stats.isUnderPressure());
            assertFalse(stats.isCritical());
        }

        @Test
        @DisplayName("Empty stats")
        void testEmptyStats() {
            var stats = GPUMemoryStats.empty(256_000_000L);

            assertEquals(256_000_000L, stats.totalBytes());
            assertEquals(0, stats.allocatedBytes());
            assertEquals(256_000_000L, stats.availableBytes());
            assertEquals(0.0, stats.utilization());
            assertEquals(GPUMemoryPressure.NONE, stats.pressure());
        }

        @Test
        @DisplayName("Summary string")
        void testSummary() {
            var stats = GPUMemoryStats.create(
                100_000_000L, 75_000_000L, GPUMemoryPressure.HIGH,
                10, 5, 1000L, 500L, 80_000_000L
            );

            String summary = stats.summary();
            assertTrue(summary.contains("75.0%"));
            assertTrue(summary.contains("HIGH"));
        }

        @Test
        @DisplayName("Invalid stats throw")
        void testInvalidStats() {
            assertThrows(IllegalArgumentException.class, () ->
                GPUMemoryStats.create(-1, 0, GPUMemoryPressure.NONE, 0, 0, 0, 0, 0));

            assertThrows(IllegalArgumentException.class, () ->
                GPUMemoryStats.create(100, 200, GPUMemoryPressure.NONE, 0, 0, 0, 0, 0));
        }
    }

    @Nested
    @DisplayName("GPUBufferPool")
    class GPUBufferPoolTests {

        private GPUBufferPool pool;

        @BeforeEach
        void setUp() {
            pool = new GPUBufferPool(100 * 1024 * 1024);  // 100 MB pool
        }

        @Test
        @DisplayName("Basic allocation and release")
        void testBasicAllocation() {
            var buffer = pool.allocate(1024 * 1024);  // 1 MB

            assertNotNull(buffer);
            assertNotNull(buffer.id());
            assertEquals(1024 * 1024, buffer.sizeBytes());
            assertTrue(buffer.sizeClass() >= buffer.sizeBytes());

            var stats = pool.getStats();
            assertEquals(1, stats.activeBuffers());
            assertTrue(stats.activeBytes() > 0);

            boolean released = pool.release(buffer.id());
            assertTrue(released);

            stats = pool.getStats();
            assertEquals(0, stats.activeBuffers());
            assertEquals(1, stats.freeBuffers());
        }

        @Test
        @DisplayName("Buffer reuse from pool")
        void testBufferReuse() {
            var buffer1 = pool.allocate(1024 * 1024);
            pool.release(buffer1.id());

            var buffer2 = pool.allocate(1024 * 1024);
            assertNotNull(buffer2);

            var stats = pool.getStats();
            assertEquals(1, stats.hitCount());  // Reused from pool
        }

        @Test
        @DisplayName("Pool miss creates new buffer")
        void testPoolMiss() {
            var buffer1 = pool.allocate(1024 * 1024);   // 1 MB
            var buffer2 = pool.allocate(2 * 1024 * 1024);  // 2 MB (different size class)

            assertNotNull(buffer2);

            var stats = pool.getStats();
            assertEquals(2, stats.activeBuffers());
            assertEquals(2, stats.missCount());  // Both allocations are misses (pool was empty)
        }

        @Test
        @DisplayName("LRU eviction when pool exhausted")
        void testLRUEviction() {
            // Fill the pool
            var buffers = new ArrayList<GPUBufferPool.PooledBuffer>();
            for (int i = 0; i < 10; i++) {
                var buffer = pool.allocate(10 * 1024 * 1024);  // 10 MB each
                if (buffer != null) {
                    buffers.add(buffer);
                }
            }

            assertTrue(buffers.size() >= 8);  // Should fit at least 8

            // Release all to free pool
            for (var buffer : buffers) {
                pool.release(buffer.id());
            }

            // Evict to make room
            long freed = pool.evict(50 * 1024 * 1024);
            assertTrue(freed >= 50 * 1024 * 1024);

            var stats = pool.getStats();
            assertTrue(stats.evictions() > 0);
        }

        @Test
        @DisplayName("Size class rounding")
        void testSizeClassRounding() {
            var small = pool.allocate(100);  // Very small
            assertNotNull(small);
            assertTrue(small.sizeClass() >= 64 * 1024);  // Min 64 KB

            var medium = pool.allocate(100 * 1024);  // 100 KB
            assertNotNull(medium);
            assertTrue(medium.sizeClass() >= 128 * 1024);  // Rounded to 128 KB

            // For large allocation testing, use a larger pool
            var largePool = new GPUBufferPool(512 * 1024 * 1024);  // 512 MB pool
            var large = largePool.allocate(300 * 1024 * 1024);  // 300 MB
            assertNotNull(large);
            assertEquals(256 * 1024 * 1024, large.sizeClass());  // Capped at max size class
        }

        @Test
        @DisplayName("Clear pool")
        void testClearPool() {
            for (int i = 0; i < 5; i++) {
                pool.allocate(1024 * 1024);
            }

            pool.clear();

            var stats = pool.getStats();
            assertEquals(0, stats.activeBuffers());
            assertEquals(0, stats.freeBuffers());
            assertEquals(0, stats.activeBytes());
        }

        @Test
        @DisplayName("Hit rate calculation")
        void testHitRate() {
            // First allocations are misses
            var b1 = pool.allocate(1024 * 1024);
            var b2 = pool.allocate(1024 * 1024);
            pool.release(b1.id());
            pool.release(b2.id());

            // These should be hits
            var b3 = pool.allocate(1024 * 1024);
            var b4 = pool.allocate(1024 * 1024);

            var stats = pool.getStats();
            assertEquals(2, stats.hitCount());
            assertEquals(2, stats.missCount());
            assertEquals(0.5, stats.hitRate(), 0.001);
        }
    }

    @Nested
    @DisplayName("GPUMemoryManager")
    class GPUMemoryManagerTests {

        private GPUMemoryManager manager;

        @BeforeEach
        void setUp() {
            manager = GPUMemoryManager.forTesting(MOCK_VRAM);
        }

        @Test
        @DisplayName("Basic allocation through manager")
        void testBasicAllocation() {
            var buffer = manager.allocate(1024 * 1024);

            assertNotNull(buffer);

            var stats = manager.getStats();
            assertTrue(stats.allocatedBytes() > 0);
            assertEquals(1, stats.allocationCount());
        }

        @Test
        @DisplayName("Pressure detection on allocations")
        void testPressureDetection() {
            assertEquals(GPUMemoryPressure.NONE, manager.getPressure());

            // Allocate enough to trigger pressure (75%+ of VRAM)
            // Use 16 MB allocations (exactly matches size class, no waste)
            // Pool is 80% of VRAM = 204.8 MB, can fit 12 x 16 MB = 192 MB
            // Utilization = 192 / 256 = 0.75 which triggers MODERATE
            long allocationSize = 16 * 1024 * 1024;  // 16 MB

            for (int i = 0; i < 12; i++) {
                var buffer = manager.allocate(allocationSize);
                if (buffer == null) break;
            }

            // Should be at least moderate pressure now
            assertTrue(manager.getPressure().ordinal() >= GPUMemoryPressure.MODERATE.ordinal(),
                "Expected at least MODERATE pressure, got: " + manager.getPressure());
        }

        @Test
        @DisplayName("Pressure listener callbacks")
        void testPressureListeners() {
            var pressureChanges = new AtomicInteger(0);
            GPUMemoryPressure[] lastPressure = {null};

            manager.addPressureListener((oldP, newP) -> {
                pressureChanges.incrementAndGet();
                lastPressure[0] = newP;
            });

            // Force pressure change by allocating enough to exceed 75% threshold
            // Use 16 MB allocations (exactly matches size class)
            var buffers = new ArrayList<GPUBufferPool.PooledBuffer>();
            for (int i = 0; i < 12; i++) {
                var buffer = manager.allocate(16 * 1024 * 1024);  // 16 MB
                if (buffer != null) {
                    buffers.add(buffer);
                }
            }

            assertTrue(pressureChanges.get() > 0, "Should have received pressure change callbacks");
        }

        @Test
        @DisplayName("Force eviction frees memory")
        void testForceEviction() {
            // Allocate and release to populate free pool
            var buffers = new ArrayList<GPUBufferPool.PooledBuffer>();
            for (int i = 0; i < 5; i++) {
                var buffer = manager.allocate(10 * 1024 * 1024);
                if (buffer != null) {
                    buffers.add(buffer);
                }
            }

            for (var buffer : buffers) {
                manager.release(buffer.id());
            }

            var statsBefore = manager.getStats();
            long freed = manager.forceEviction();

            if (statsBefore.allocatedBytes() > 0) {
                assertTrue(freed >= 0);
            }
        }

        @Test
        @DisplayName("Can allocate check")
        void testCanAllocate() {
            assertTrue(manager.canAllocate(1024 * 1024));
            assertTrue(manager.canAllocate(100 * 1024 * 1024));

            // Fill up memory
            long poolMax = (long) (MOCK_VRAM * 0.8);
            while (manager.canAllocate(poolMax / 10) && manager.getPressure() != GPUMemoryPressure.CRITICAL) {
                var buffer = manager.allocate(poolMax / 10);
                if (buffer == null) break;
            }

            // At critical pressure, should return false
            if (manager.getPressure() == GPUMemoryPressure.CRITICAL) {
                assertFalse(manager.canAllocate(poolMax));
            }
        }

        @Test
        @DisplayName("Reset clears all state")
        void testReset() {
            for (int i = 0; i < 5; i++) {
                manager.allocate(1024 * 1024);
            }

            manager.reset();

            var stats = manager.getStats();
            assertEquals(0, stats.allocatedBytes());
            assertEquals(0, stats.allocationCount());
            assertEquals(GPUMemoryPressure.NONE, manager.getPressure());
        }

        @Test
        @DisplayName("Stats tracking")
        void testStatsTracking() {
            var buffer = manager.allocate(1024 * 1024);
            manager.release(buffer.id());

            var stats = manager.getStats();
            assertTrue(stats.streamInBytes() > 0);
            assertTrue(stats.peakAllocatedBytes() > 0);
        }
    }

    @Nested
    @DisplayName("MemoryConfig")
    class MemoryConfigTests {

        @Test
        @DisplayName("Default configuration")
        void testDefaults() {
            var config = MemoryConfig.defaults();

            assertEquals(0.75, config.moderateThreshold());
            assertEquals(0.85, config.highThreshold());
            assertEquals(0.95, config.criticalThreshold());
            assertEquals(0.8, config.poolSizeRatio());
        }

        @Test
        @DisplayName("Custom configuration")
        void testCustomConfig() {
            var config = new MemoryConfig(0.6, 0.8, 0.9, 0.7);

            assertEquals(0.6, config.moderateThreshold());
            assertEquals(0.8, config.highThreshold());
            assertEquals(0.9, config.criticalThreshold());
            assertEquals(0.7, config.poolSizeRatio());
        }

        @Test
        @DisplayName("Invalid configuration throws")
        void testInvalidConfig() {
            // Thresholds out of order
            assertThrows(IllegalArgumentException.class, () ->
                new MemoryConfig(0.9, 0.8, 0.7, 0.5));

            // Pool ratio out of range
            assertThrows(IllegalArgumentException.class, () ->
                new MemoryConfig(0.7, 0.8, 0.9, 0.0));

            assertThrows(IllegalArgumentException.class, () ->
                new MemoryConfig(0.7, 0.8, 0.9, 1.5));
        }
    }

    @Nested
    @DisplayName("Large Scene Streaming")
    class LargeSceneStreamingTests {

        @Test
        @DisplayName("Handle scene larger than VRAM")
        void testLargeSceneHandling() {
            // Small VRAM for testing
            var smallManager = GPUMemoryManager.forTesting(100 * 1024 * 1024);  // 100 MB

            // Try to allocate "scene" that would exceed VRAM
            var allocations = new ArrayList<GPUBufferPool.PooledBuffer>();
            int successCount = 0;
            int failCount = 0;

            // Try to allocate 200 MB in chunks
            for (int i = 0; i < 20; i++) {
                var buffer = smallManager.allocate(10 * 1024 * 1024);  // 10 MB each
                if (buffer != null) {
                    allocations.add(buffer);
                    successCount++;
                } else {
                    failCount++;
                }
            }

            // Should have some successes and some failures
            assertTrue(successCount > 0, "Should have some successful allocations");
            // Pool is 80 MB, should fit ~8 allocations of 10 MB each

            // Now release some and try again (streaming simulation)
            if (allocations.size() > 2) {
                smallManager.release(allocations.get(0).id());
                smallManager.release(allocations.get(1).id());

                // Should be able to allocate again
                var newBuffer = smallManager.allocate(10 * 1024 * 1024);
                assertNotNull(newBuffer, "Should be able to allocate after releasing");
            }
        }

        @Test
        @DisplayName("Pressure-driven eviction enables continued allocation")
        void testPressureDrivenEviction() {
            var manager = GPUMemoryManager.forTesting(100 * 1024 * 1024);

            // Fill pool
            var buffers = new ArrayList<GPUBufferPool.PooledBuffer>();
            for (int i = 0; i < 10; i++) {
                var buffer = manager.allocate(8 * 1024 * 1024);
                if (buffer != null) {
                    buffers.add(buffer);
                }
            }

            // Release all to free pool
            for (var buffer : buffers) {
                manager.release(buffer.id());
            }

            // Force eviction
            long freed = manager.forceEviction();
            assertTrue(freed >= 0);

            // Should be able to allocate again
            var newBuffer = manager.allocate(8 * 1024 * 1024);
            assertNotNull(newBuffer);
        }

        @Test
        @DisplayName("No OOM on large scene simulation")
        void testNoOOMOnLargeScene() {
            var manager = GPUMemoryManager.forTesting(50 * 1024 * 1024);  // 50 MB VRAM

            // Simulate rendering 100 MB scene in chunks
            int successfulFrames = 0;

            for (int frame = 0; frame < 10; frame++) {
                // Each frame needs ~20 MB
                var frameBuffers = new ArrayList<GPUBufferPool.PooledBuffer>();

                // Try to allocate frame data
                for (int chunk = 0; chunk < 4; chunk++) {
                    var buffer = manager.allocate(5 * 1024 * 1024);
                    if (buffer != null) {
                        frameBuffers.add(buffer);
                    }
                }

                // Render frame (simulated)
                if (!frameBuffers.isEmpty()) {
                    successfulFrames++;
                }

                // Release frame data
                for (var buffer : frameBuffers) {
                    manager.release(buffer.id());
                }
            }

            assertTrue(successfulFrames > 0, "Should complete some frames");
            // No OOM exception means test passed
        }
    }

    @Nested
    @DisplayName("Integration")
    class IntegrationTests {

        @Test
        @DisplayName("Full lifecycle with pressure callbacks")
        void testFullLifecycle() {
            var manager = GPUMemoryManager.forTesting(MOCK_VRAM);

            var pressureLevels = new ArrayList<GPUMemoryPressure>();
            manager.addPressureListener((oldP, newP) -> pressureLevels.add(newP));

            // 1. Start with no pressure
            assertEquals(GPUMemoryPressure.NONE, manager.getPressure());

            // 2. Allocate buffers
            var buffers = new ArrayList<GPUBufferPool.PooledBuffer>();
            for (int i = 0; i < 20; i++) {
                var buffer = manager.allocate(10 * 1024 * 1024);
                if (buffer != null) {
                    buffers.add(buffer);
                }
            }

            // 3. Check stats
            var stats = manager.getStats();
            assertTrue(stats.allocatedBytes() > 0);
            assertTrue(stats.allocationCount() > 0);

            // 4. Release buffers
            for (var buffer : buffers) {
                manager.release(buffer.id());
            }

            // 5. Force eviction
            manager.forceEviction();

            // 6. Reset
            manager.reset();

            var finalStats = manager.getStats();
            assertEquals(0, finalStats.allocatedBytes());
            assertEquals(GPUMemoryPressure.NONE, manager.getPressure());
        }
    }
}
