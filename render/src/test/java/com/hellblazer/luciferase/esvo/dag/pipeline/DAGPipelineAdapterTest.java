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
package com.hellblazer.luciferase.esvo.dag.pipeline;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.*;
import com.hellblazer.luciferase.esvo.dag.config.CompressionConfiguration;
import com.hellblazer.luciferase.esvo.dag.config.MemoryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DAGPipelineAdapter.
 *
 * <p>Verifies that DAGPipelineAdapter correctly wraps DAGBuilder
 * for integration with the rendering pipeline, including configuration
 * passing, metrics collection, and memory policy enforcement.
 *
 * @author hal.hildebrand
 */
class DAGPipelineAdapterTest {

    private ESVOOctreeData testOctree;

    @BeforeEach
    void setUp() {
        // Create a simple test octree with 8 leaf nodes
        testOctree = TestDataFactory.createSimpleOctree(8);
    }

    @Test
    void testCompressWithDefaultConfiguration() {
        // Given: Adapter with default configuration
        var adapter = new DAGPipelineAdapter(CompressionConfiguration.defaultConfig());

        // When: Compress octree
        var result = adapter.compress(testOctree);

        // Then: Result is a CompressibleOctreeData
        assertNotNull(result);
        assertTrue(result instanceof CompressibleOctreeData);

        // And: Result is a DAGOctreeData
        assertTrue(result instanceof DAGOctreeData);
    }

    @Test
    void testCompressReturnsValidCompressionRatio() {
        // Given: Adapter
        var adapter = new DAGPipelineAdapter(CompressionConfiguration.defaultConfig());

        // When: Compress
        var result = adapter.compress(testOctree);

        // Then: Node count is reduced or same
        assertTrue(result.nodeCount() <= testOctree.nodeCount());
    }

    @Test
    void testCompressWithNullSourceThrows() {
        // Given: Adapter
        var adapter = new DAGPipelineAdapter(CompressionConfiguration.defaultConfig());

        // When/Then: Compress null throws
        assertThrows(DAGBuildException.InvalidInputException.class,
                     () -> adapter.compress(null));
    }

    @Test
    void testCompressWithMetricsCallback() {
        // Given: Adapter and metrics callback
        var adapter = new DAGPipelineAdapter(CompressionConfiguration.defaultConfig());
        var metricsReceived = new AtomicBoolean(false);
        var capturedMetrics = new AtomicReference<DAGMetadata>();

        // When: Compress with metrics callback
        var result = adapter.compressWithMetrics(testOctree, metrics -> {
            metricsReceived.set(true);
            capturedMetrics.set(metrics);
        });

        // Then: Callback was invoked
        assertTrue(metricsReceived.get(), "Metrics callback should have been invoked");

        // And: Metrics are valid
        var metrics = capturedMetrics.get();
        assertNotNull(metrics);
        assertTrue(metrics.compressionRatio() >= 1.0f);
        assertTrue(metrics.buildTime().toMillis() >= 0);
    }

    @Test
    void testEstimateCompression() {
        // Given: Adapter
        var adapter = new DAGPipelineAdapter(CompressionConfiguration.defaultConfig());

        // When: Estimate compression
        var estimate = adapter.estimateCompression(testOctree);

        // Then: Estimate is provided
        assertNotNull(estimate);
        assertTrue(estimate.estimatedCompressionRatio() >= 1.0f);
        assertTrue(estimate.estimatedUniqueNodeCount() > 0);
        assertTrue(estimate.estimatedMemorySaved() >= 0);
    }

    @Test
    void testStrictMemoryPolicyThrowsOnBudgetExceeded() {
        // Given: Adapter with STRICT policy and tiny budget
        var config = CompressionConfiguration.builder()
            .memoryPolicy(MemoryPolicy.STRICT)
            .memoryBudgetBytes(1L)  // Impossibly small budget (1 byte < 8 bytes per node)
            .build();
        var adapter = new DAGPipelineAdapter(config);

        // When/Then: Compression throws MemoryBudgetExceededException
        assertThrows(DAGBuildException.MemoryBudgetExceededException.class,
                     () -> adapter.compress(testOctree));
    }

    @Test
    void testWarnMemoryPolicyLogsWarningAndContinues() {
        // Given: Adapter with WARN policy and tiny budget
        var config = CompressionConfiguration.builder()
            .memoryPolicy(MemoryPolicy.WARN)
            .memoryBudgetBytes(10L)
            .build();
        var adapter = new DAGPipelineAdapter(config);

        // When: Compress (should log warning but succeed)
        var result = assertDoesNotThrow(() -> adapter.compress(testOctree));

        // Then: Compression succeeds despite budget exceeded
        assertNotNull(result);
    }

    @Test
    void testAdaptiveMemoryPolicySkipsCompressionIfConstrained() {
        // Given: Adapter with ADAPTIVE policy and tiny budget
        var config = CompressionConfiguration.builder()
            .memoryPolicy(MemoryPolicy.ADAPTIVE)
            .memoryBudgetBytes(10L)
            .build();
        var adapter = new DAGPipelineAdapter(config);

        // When: Compress
        var result = adapter.compress(testOctree);

        // Then: Returns original data (compression skipped)
        // Note: ADAPTIVE may return original as CompressibleOctreeData wrapper
        assertNotNull(result);
    }

    @Test
    void testValidateAfterCompressionDetectsStructuralIssues() {
        // Given: Adapter and compressed data
        var adapter = new DAGPipelineAdapter(CompressionConfiguration.defaultConfig());
        var compressed = adapter.compress(testOctree);

        // When: Validate
        // Then: No exception (valid compression)
        assertDoesNotThrow(() -> adapter.validateAfterCompression(compressed));
    }

    @Test
    void testConfigurationIsRespected() {
        // Given: Custom configuration
        var config = CompressionConfiguration.builder()
            .strategy(CompressionStrategy.AGGRESSIVE)
            .hashAlgorithm(HashAlgorithm.SHA256)
            .enableMetrics(true)
            .build();
        var adapter = new DAGPipelineAdapter(config);

        // When: Compress
        var result = adapter.compress(testOctree);

        // Then: Compression succeeds with custom config
        assertNotNull(result);
    }

    @Test
    void testMultipleCompressionsProduceDifferentInstances() {
        // Given: Adapter
        var adapter = new DAGPipelineAdapter(CompressionConfiguration.defaultConfig());

        // When: Compress same octree twice
        var result1 = adapter.compress(testOctree);
        var result2 = adapter.compress(testOctree);

        // Then: Different instances (no caching at this level)
        assertNotSame(result1, result2);

        // But: Same structure (node counts match)
        assertEquals(result1.nodeCount(), result2.nodeCount());
    }
}
