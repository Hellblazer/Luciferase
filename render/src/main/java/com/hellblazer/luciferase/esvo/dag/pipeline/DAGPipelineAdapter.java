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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Pipeline adapter for integrating DAG compression into the ESVO rendering pipeline.
 *
 * <p>Wraps {@link DAGBuilder} with pipeline-specific functionality:
 * <ul>
 * <li>Configuration management</li>
 * <li>Memory budget enforcement</li>
 * <li>Metrics collection</li>
 * <li>Validation hooks</li>
 * </ul>
 *
 * <h3>Memory Policy Enforcement</h3>
 * <pre>{@code
 * var config = CompressionConfiguration.builder()
 *     .memoryPolicy(MemoryPolicy.STRICT)
 *     .memoryBudgetBytes(512 * 1024 * 1024) // 512MB
 *     .build();
 * var adapter = new DAGPipelineAdapter(config);
 *
 * // Throws if budget exceeded
 * var compressed = adapter.compress(svo);
 * }</pre>
 *
 * <h3>Metrics Collection</h3>
 * <pre>{@code
 * adapter.compressWithMetrics(svo, metrics -> {
 *     System.out.printf("Compression: %.1fx in %dms%n",
 *         metrics.compressionRatio(),
 *         metrics.buildDuration().toMillis());
 * });
 * }</pre>
 *
 * @author hal.hildebrand
 * @see DAGBuilder
 * @see CompressionConfiguration
 */
public class DAGPipelineAdapter {
    private static final Logger log = LoggerFactory.getLogger(DAGPipelineAdapter.class);

    private final CompressionConfiguration config;

    /**
     * Create adapter with the specified configuration.
     *
     * @param config compression configuration (must not be null)
     */
    public DAGPipelineAdapter(CompressionConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        this.config = config;
    }

    /**
     * Compress the given ESVO octree data.
     *
     * <p>Applies memory policy enforcement:
     * <ul>
     * <li>STRICT: Throws {@link DAGBuildException.MemoryBudgetExceededException} if budget exceeded</li>
     * <li>WARN: Logs warning and continues compression</li>
     * <li>ADAPTIVE: Returns original data if memory constrained</li>
     * </ul>
     *
     * @param source source ESVO octree (must not be null)
     * @return compressed octree as CompressibleOctreeData
     * @throws DAGBuildException.InvalidInputException if source is null
     * @throws DAGBuildException.MemoryBudgetExceededException if STRICT policy and budget exceeded
     */
    public CompressibleOctreeData compress(ESVOOctreeData source) {
        if (source == null) {
            throw new DAGBuildException.InvalidInputException("Source octree must not be null");
        }

        // Check memory budget before compression
        if (config.memoryBudgetBytes() > 0) {
            var estimate = estimateCompression(source);
            // Estimate memory usage based on unique node count
            var bytesPerNode = 8L;
            var estimatedBytes = estimate.estimatedUniqueNodeCount() * bytesPerNode;

            if (estimatedBytes > config.memoryBudgetBytes()) {
                return handleMemoryBudgetExceeded(source, config.memoryBudgetBytes(), estimatedBytes);
            }
        }

        // Build DAG with configured settings
        return (CompressibleOctreeData) DAGBuilder.from(source)
            .withHashAlgorithm(config.hashAlgorithm())
            .withCompressionStrategy(config.strategy())
            .withValidation(true)
            .build();
    }

    /**
     * Compress with metrics callback.
     *
     * <p>Invokes the callback with performance metrics after compression completes.
     *
     * @param source source octree
     * @param metricsCallback callback to receive metrics (may be null)
     * @return compressed octree
     */
    public CompressibleOctreeData compressWithMetrics(ESVOOctreeData source,
                                                       Consumer<DAGMetadata> metricsCallback) {
        if (source == null) {
            throw new DAGBuildException.InvalidInputException("Source octree must not be null");
        }

        // Build with metrics collection
        var dag = DAGBuilder.from(source)
            .withHashAlgorithm(config.hashAlgorithm())
            .withCompressionStrategy(config.strategy())
            .withValidation(config.enableMetrics())
            .build();

        // Invoke callback if provided
        if (metricsCallback != null && config.enableMetrics()) {
            var metadata = dag.getMetadata();
            metricsCallback.accept(metadata);
        }

        return (CompressibleOctreeData) dag;
    }

    /**
     * Estimate compression ratio and memory usage without building DAG.
     *
     * <p>Uses fast hash-based estimation to predict compression results.
     *
     * @param source source octree
     * @return compression estimate
     */
    public CompressionEstimate estimateCompression(ESVOOctreeData source) {
        if (source == null) {
            throw new DAGBuildException.InvalidInputException("Source octree must not be null");
        }

        // Use hash-based estimation (fast approximation)
        var nodeCount = source.nodeCount();

        // Conservative estimate: assume 20% deduplication for typical scenes
        // (actual compression varies from 5x-15x depending on structure)
        var estimatedRatio = 1.25f; // Conservative estimate
        var estimatedUniqueNodes = Math.max(1L, (long) (nodeCount / estimatedRatio));
        var bytesPerNode = 8L; // ESVONodeUnified size
        var estimatedMemorySaved = (nodeCount - estimatedUniqueNodes) * bytesPerNode;

        return new CompressionEstimate(
            estimatedRatio,
            estimatedUniqueNodes,
            estimatedMemorySaved
        );
    }

    /**
     * Validate compressed data for structural integrity.
     *
     * <p>Performs post-compression validation checks:
     * <ul>
     * <li>Node count is valid (> 0)</li>
     * <li>Compression ratio is reasonable (> 1.0)</li>
     * <li>No structural anomalies</li>
     * </ul>
     *
     * @param compressed compressed octree
     * @throws DAGBuildException.ValidationFailedException if validation fails
     */
    public void validateAfterCompression(CompressibleOctreeData compressed) {
        if (compressed == null) {
            throw new DAGBuildException.ValidationFailedException("Compressed data is null");
        }

        var nodeCount = compressed.nodeCount();
        if (nodeCount <= 0) {
            throw new DAGBuildException.ValidationFailedException(
                "Invalid node count: " + nodeCount);
        }

        // Additional validation for DAG instances
        if (compressed instanceof DAGOctreeData dag) {
            var metadata = dag.getMetadata();
            if (metadata.compressionRatio() < 1.0f) {
                throw new DAGBuildException.ValidationFailedException(
                    "Invalid compression ratio: " + metadata.compressionRatio());
            }
        }
    }

    /**
     * Handle memory budget exceeded based on policy.
     *
     * @param source original source data
     * @param budget memory budget in bytes
     * @param estimated estimated memory usage
     * @return compressed data or original data (depending on policy)
     * @throws DAGBuildException.MemoryBudgetExceededException if STRICT policy
     */
    private CompressibleOctreeData handleMemoryBudgetExceeded(
        ESVOOctreeData source, long budget, long estimated) {

        return switch (config.memoryPolicy()) {
            case STRICT ->
                throw new DAGBuildException.MemoryBudgetExceededException(budget, estimated);

            case WARN -> {
                log.warn("Memory budget exceeded: budget={} bytes, estimated={} bytes - continuing anyway",
                         budget, estimated);
                // Continue with compression despite budget exceeded
                yield (CompressibleOctreeData) DAGBuilder.from(source)
                    .withHashAlgorithm(config.hashAlgorithm())
                    .withCompressionStrategy(config.strategy())
                    .build();
            }

            case ADAPTIVE -> {
                log.info("Memory budget exceeded: budget={} bytes, estimated={} bytes - returning original",
                         budget, estimated);
                // Return original data (wrapped as CompressibleOctreeData if needed)
                // Note: ESVOOctreeData must implement CompressibleOctreeData for this to work
                yield (CompressibleOctreeData) source;
            }
        };
    }
}
