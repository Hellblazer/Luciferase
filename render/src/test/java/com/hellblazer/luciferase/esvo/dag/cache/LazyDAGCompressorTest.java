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
package com.hellblazer.luciferase.esvo.dag.cache;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.*;
import com.hellblazer.luciferase.esvo.dag.pipeline.CompressibleOctreeData;
import com.hellblazer.luciferase.esvo.dag.pipeline.DAGPipelineAdapter;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LazyDAGCompressor.
 *
 * @author hal.hildebrand
 */
class LazyDAGCompressorTest {

    private ScheduledExecutorService executor;
    private DAGPipelineAdapter adapter;
    private LazyDAGCompressor compressor;

    @BeforeEach
    void setUp() {
        executor = Executors.newScheduledThreadPool(2);
        adapter = new StubAdapter();
        compressor = new LazyDAGCompressor(executor, adapter);
    }

    @AfterEach
    void tearDown() {
        compressor.shutdown();
        executor.shutdown();
    }

    @Test
    void testCompressAsync() throws ExecutionException, InterruptedException, TimeoutException {
        var source = createESVOData();

        var future = compressor.compressAsync(source);

        assertNotNull(future);
        var result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result instanceof DAGOctreeData);
    }

    @Test
    void testCompressAsync_NullSource() {
        assertThrows(NullPointerException.class, () -> compressor.compressAsync(null));
    }

    @Test
    void testScheduleCompression() throws InterruptedException {
        var source = createESVOData();

        var latch = new CountDownLatch(1);
        compressor.scheduleCompression(source, 100, result -> {
            assertNotNull(result);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Compression should complete within timeout");
    }

    @Test
    void testScheduleCompression_NullSource() {
        assertThrows(NullPointerException.class, () -> compressor.scheduleCompression(null, 100, result -> {}));
    }

    @Test
    void testScheduleCompression_NegativeDelay() {
        var source = createESVOData();
        assertThrows(IllegalArgumentException.class, () -> compressor.scheduleCompression(source, -1, result -> {}));
    }

    @Test
    void testScheduleCompression_NullCallback() {
        var source = createESVOData();
        assertThrows(NullPointerException.class, () -> compressor.scheduleCompression(source, 100, null));
    }

    @Test
    void testMultipleAsyncCompressions() throws ExecutionException, InterruptedException, TimeoutException {
        var source1 = createESVOData();
        var source2 = createESVOData();

        var future1 = compressor.compressAsync(source1);
        var future2 = compressor.compressAsync(source2);

        assertNotNull(future1.get(5, TimeUnit.SECONDS));
        assertNotNull(future2.get(5, TimeUnit.SECONDS));
    }

    @Test
    void testExceptionHandling() {
        var failingAdapter = new StubAdapter(true);
        var failingCompressor = new LazyDAGCompressor(executor, failingAdapter);
        var source = createESVOData();

        var future = failingCompressor.compressAsync(source);

        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void testGracefulShutdown() throws InterruptedException {
        var source = createESVOData();

        compressor.compressAsync(source);
        compressor.shutdown();

        assertTrue(compressor.awaitCompletion(5000), "Should complete within timeout");
    }

    @Test
    void testShutdown_CancelsScheduledTasks() throws InterruptedException {
        var source = createESVOData();

        var latch = new CountDownLatch(1);
        compressor.scheduleCompression(source, 10000, result -> latch.countDown()); // Long delay

        compressor.shutdown();

        assertFalse(latch.await(1, TimeUnit.SECONDS), "Scheduled task should be cancelled");
    }

    @Test
    void testAwaitCompletion_Timeout() throws ExecutionException, InterruptedException {
        var slowAdapter = new StubAdapter(5000); // 5 second delay
        var slowExecutor = Executors.newScheduledThreadPool(2);
        var slowCompressor = new LazyDAGCompressor(slowExecutor, slowAdapter);
        var source = createESVOData();

        // Start a slow compression
        var future = slowCompressor.compressAsync(source);

        // Give it a moment to actually start
        Thread.sleep(50);

        slowCompressor.shutdown();

        // Wait only 100ms - should timeout since task takes 5 seconds
        var startTime = System.currentTimeMillis();
        var result = slowCompressor.awaitCompletion(100);
        var elapsedTime = System.currentTimeMillis() - startTime;

        assertFalse(result, "Should timeout");
        assertTrue(elapsedTime >= 100 && elapsedTime < 1000, "Should wait approximately 100ms");

        // Cleanup - wait for actual completion
        slowExecutor.shutdown();
        assertTrue(slowExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void testScheduleCompression_DelayTiming() throws InterruptedException {
        var source = createESVOData();

        var startTime = System.currentTimeMillis();
        var latch = new CountDownLatch(1);

        compressor.scheduleCompression(source, 500, result -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var elapsedTime = System.currentTimeMillis() - startTime;
        assertTrue(elapsedTime >= 500, "Compression should wait at least 500ms");
    }

    @Test
    void testMultipleScheduledCompressions() throws InterruptedException {
        var source1 = createESVOData();
        var source2 = createESVOData();
        var source3 = createESVOData();

        var latch = new CountDownLatch(3);

        compressor.scheduleCompression(source1, 100, result -> latch.countDown());
        compressor.scheduleCompression(source2, 200, result -> latch.countDown());
        compressor.scheduleCompression(source3, 300, result -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All compressions should complete");
    }

    @Test
    void testScheduleCompression_ExceptionInCallback() throws InterruptedException {
        var source = createESVOData();

        var latch = new CountDownLatch(1);

        compressor.scheduleCompression(source, 100, result -> {
            latch.countDown();
            throw new RuntimeException("Callback exception");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Compression should complete despite callback exception");
    }

    /**
     * Create simple ESVO data for testing.
     */
    private ESVOOctreeData createESVOData() {
        var octree = new ESVOOctreeData(1024);
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0);
        octree.setNode(0, root);
        return octree;
    }

    /**
     * Stub adapter for testing.
     */
    private static class StubAdapter extends DAGPipelineAdapter {
        private final boolean shouldFail;
        private final long delayMs;

        StubAdapter() {
            this(false, 0);
        }

        StubAdapter(boolean shouldFail) {
            this(shouldFail, 0);
        }

        StubAdapter(long delayMs) {
            this(false, delayMs);
        }

        StubAdapter(boolean shouldFail, long delayMs) {
            super(com.hellblazer.luciferase.esvo.dag.config.CompressionConfiguration.defaultConfig());
            this.shouldFail = shouldFail;
            this.delayMs = delayMs;
        }

        @Override
        public CompressibleOctreeData compress(ESVOOctreeData source) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

            if (shouldFail) {
                throw new RuntimeException("Compression failed");
            }

            // Return simple stub DAG data
            return new StubDAGData(source.getNodeCount());
        }
    }

    /**
     * Minimal stub DAG data for testing.
     */
    private static class StubDAGData implements DAGOctreeData {
        private final int nodeCount;

        StubDAGData(int nodeCount) {
            this.nodeCount = nodeCount;
        }

        @Override
        public int nodeCount() {
            return nodeCount;
        }

        @Override
        public ESVONodeUnified[] nodes() {
            return new ESVONodeUnified[nodeCount];
        }

        @Override
        public PointerAddressingMode getAddressingMode() {
            return PointerAddressingMode.ABSOLUTE;
        }

        @Override
        public DAGMetadata getMetadata() {
            return new DAGMetadata(nodeCount, nodeCount, 8, 0,
                                   Map.of(), Duration.ZERO,
                                   HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        }

        @Override
        public float getCompressionRatio() {
            return 1.0f;
        }

        @Override
        public ByteBuffer nodesToByteBuffer() {
            return ByteBuffer.allocate(nodeCount * 8);
        }

        @Override
        public int[] getFarPointers() {
            return new int[0];
        }

        @Override
        public CoordinateSpace getCoordinateSpace() {
            return CoordinateSpace.UNIT_CUBE;
        }

        @Override
        public int sizeInBytes() {
            return nodeCount * 8;
        }

        @Override
        public int maxDepth() {
            return 1;
        }

        @Override
        public int leafCount() {
            return 0;
        }

        @Override
        public int internalCount() {
            return nodeCount;
        }
    }
}
