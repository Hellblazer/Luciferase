/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.CompressionStrategy;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.HashAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first implementation of CompressionMetricsCollector - compression performance tracking.
 *
 * @author hal.hildebrand
 */
class CompressionMetricsCollectorTest {

    @Test
    void testInitialState() {
        var collector = new CompressionMetricsCollector();
        var summary = collector.getSummary();

        assertEquals(0, summary.totalCompressions());
        assertEquals(0L, summary.totalTimeMs());
        assertEquals(0.0, summary.averageTimeMs(), 0.01);
        assertEquals(0L, summary.minTimeMs());
        assertEquals(0L, summary.maxTimeMs());
    }

    @Test
    void testRecordSingleCompression() throws Exception {
        var collector = new CompressionMetricsCollector();
        var octree = createSimpleOctree();
        var result = DAGBuilder.from(octree)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .withValidation(false)
            .build();

        collector.recordCompression(octree, result);

        var summary = collector.getSummary();
        assertEquals(1, summary.totalCompressions());
        assertTrue(summary.totalTimeMs() >= 0);
        assertTrue(summary.averageTimeMs() >= 0);
        assertEquals(summary.minTimeMs(), summary.maxTimeMs()); // Single compression
    }

    @Test
    void testRecordMultipleCompressions() throws Exception {
        var collector = new CompressionMetricsCollector();

        for (int i = 0; i < 5; i++) {
            var octree = createSimpleOctree();
            var result = DAGBuilder.from(octree)
                .withHashAlgorithm(HashAlgorithm.SHA256)
                .withCompressionStrategy(CompressionStrategy.BALANCED)
                .withValidation(false)
                .build();
            collector.recordCompression(octree, result);
        }

        var summary = collector.getSummary();
        assertEquals(5, summary.totalCompressions());
        assertTrue(summary.totalTimeMs() >= 0);
        assertTrue(summary.averageTimeMs() >= 0);
        assertTrue(summary.minTimeMs() >= 0);
        assertTrue(summary.maxTimeMs() >= summary.minTimeMs());
    }

    @Test
    void testTimingMeasurement() throws Exception {
        var collector = new CompressionMetricsCollector();
        var octree = createSimpleOctree();

        var startTime = System.nanoTime();
        var result = DAGBuilder.from(octree)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .withValidation(false)
            .build();
        var endTime = System.nanoTime();

        collector.recordCompression(octree, result);

        var summary = collector.getSummary();
        var recordedTimeMs = summary.totalTimeMs();
        var actualTimeMs = (endTime - startTime) / 1_000_000;

        // Recorded time should be approximately equal to actual time
        assertTrue(Math.abs(recordedTimeMs - actualTimeMs) < 100); // Within 100ms tolerance
    }

    @Test
    void testMinMaxTracking() throws Exception {
        var collector = new CompressionMetricsCollector();

        // First compression (small)
        var octree1 = createSimpleOctree();
        var result1 = DAGBuilder.from(octree1)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .withValidation(false)
            .build();
        collector.recordCompression(octree1, result1);

        var firstMin = collector.getSummary().minTimeMs();
        var firstMax = collector.getSummary().maxTimeMs();

        // Second compression (force different timing by adding work)
        Thread.sleep(5); // Small delay to ensure different timing
        var octree2 = createLargerOctree();
        var result2 = DAGBuilder.from(octree2)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .withValidation(false)
            .build();
        collector.recordCompression(octree2, result2);

        var summary = collector.getSummary();
        assertTrue(summary.minTimeMs() <= firstMin); // Min stays same or decreases
        assertTrue(summary.maxTimeMs() >= firstMax); // Max stays same or increases
    }

    @Test
    void testAverageCalculation() throws Exception {
        var collector = new CompressionMetricsCollector();

        long totalExpected = 0;
        int count = 10;

        for (int i = 0; i < count; i++) {
            var octree = createSimpleOctree();
            var startTime = System.nanoTime();
            var result = DAGBuilder.from(octree)
                .withHashAlgorithm(HashAlgorithm.SHA256)
                .withCompressionStrategy(CompressionStrategy.BALANCED)
                .withValidation(false)
                .build();
            var endTime = System.nanoTime();
            totalExpected += (endTime - startTime) / 1_000_000;

            collector.recordCompression(octree, result);
        }

        var summary = collector.getSummary();
        var actualAvg = summary.averageTimeMs();

        // Average should be calculated correctly (total / count)
        assertEquals((double) summary.totalTimeMs() / count, actualAvg, 0.01);
    }

    @Test
    void testGetAllMetrics() throws Exception {
        var collector = new CompressionMetricsCollector();

        for (int i = 0; i < 3; i++) {
            var octree = createSimpleOctree();
            var result = DAGBuilder.from(octree)
                .withHashAlgorithm(HashAlgorithm.SHA256)
                .withCompressionStrategy(CompressionStrategy.BALANCED)
                .withValidation(false)
                .build();
            collector.recordCompression(octree, result);
        }

        var allMetrics = collector.getAllMetrics();
        assertEquals(3, allMetrics.size());

        // Each metric should have valid data
        for (var metric : allMetrics) {
            assertTrue(metric.sourceNodeCount() > 0);
            assertTrue(metric.buildTime().toMillis() >= 0);
            assertNotNull(metric.strategy());
        }
    }

    @Test
    void testResetFunctionality() throws Exception {
        var collector = new CompressionMetricsCollector();

        for (int i = 0; i < 3; i++) {
            var octree = createSimpleOctree();
            var result = DAGBuilder.from(octree)
                .withHashAlgorithm(HashAlgorithm.SHA256)
                .withCompressionStrategy(CompressionStrategy.BALANCED)
                .withValidation(false)
                .build();
            collector.recordCompression(octree, result);
        }

        collector.reset();

        var summary = collector.getSummary();
        assertEquals(0, summary.totalCompressions());
        assertEquals(0L, summary.totalTimeMs());
        assertEquals(0, collector.getAllMetrics().size());
    }

    @Test
    void testConcurrentRecording() throws Exception {
        var collector = new CompressionMetricsCollector();
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(20);

        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                try {
                    var octree = createSimpleOctree();
                    var result = DAGBuilder.from(octree)
                        .withHashAlgorithm(HashAlgorithm.SHA256)
                        .withCompressionStrategy(CompressionStrategy.BALANCED)
                        .withValidation(false)
                        .build();
                    collector.recordCompression(octree, result);
                } catch (Exception e) {
                    fail("Compression failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        var summary = collector.getSummary();
        assertEquals(20, summary.totalCompressions());
        assertEquals(20, collector.getAllMetrics().size());
    }

    @Test
    void testCompressionRatioTracking() throws Exception {
        var collector = new CompressionMetricsCollector();
        var octree = createSimpleOctree();
        var result = DAGBuilder.from(octree)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .withValidation(false)
            .build();

        collector.recordCompression(octree, result);

        var metrics = collector.getAllMetrics().get(0);
        assertTrue(metrics.compressionRatio() > 0);
    }

    @Test
    void testStrategyRecording() throws Exception {
        var collector = new CompressionMetricsCollector();
        var octree = createSimpleOctree();
        var result = DAGBuilder.from(octree)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .withValidation(false)
            .build();

        collector.recordCompression(octree, result);

        var metrics = collector.getAllMetrics().get(0);
        assertTrue(metrics.strategy().contains("HASH"));
    }

    @Test
    void testNullSourceOctreeHandling() throws Exception {
        var collector = new CompressionMetricsCollector();
        var result = createSimpleDAGResult();
        assertThrows(NullPointerException.class, () -> {
            collector.recordCompression(null, result);
        });
    }

    @Test
    void testNullResultHandling() throws Exception {
        var collector = new CompressionMetricsCollector();
        var octree = createSimpleOctree();
        assertThrows(NullPointerException.class, () -> {
            collector.recordCompression(octree, null);
        });
    }

    // Helper methods
    private ESVOOctreeData createSimpleOctree() {
        var nodes = new ESVONodeUnified[3];

        nodes[0] = new ESVONodeUnified();
        nodes[0].setChildMask(0b10000001);
        nodes[0].setChildPtr(1);

        nodes[1] = new ESVONodeUnified();
        nodes[1].setChildMask(0);
        nodes[1].setLeafMask(0xFF);

        nodes[2] = new ESVONodeUnified();
        nodes[2].setChildMask(0);
        nodes[2].setLeafMask(0xFF);

        return ESVOOctreeData.fromNodes(nodes);
    }

    private ESVOOctreeData createLargerOctree() {
        var nodes = new ESVONodeUnified[10];

        for (int i = 0; i < 10; i++) {
            nodes[i] = new ESVONodeUnified();
            nodes[i].setChildMask(0);
            nodes[i].setLeafMask(0xFF);
        }

        return ESVOOctreeData.fromNodes(nodes);
    }

    private com.hellblazer.luciferase.esvo.dag.DAGOctreeData createSimpleDAGResult() throws Exception {
        var octree = createSimpleOctree();
        return DAGBuilder.from(octree)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .withValidation(false)
            .build();
    }
}
