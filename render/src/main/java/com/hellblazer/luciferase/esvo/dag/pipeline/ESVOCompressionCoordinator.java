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
import com.hellblazer.luciferase.esvo.dag.config.CompressionConfiguration;
import com.hellblazer.luciferase.esvo.dag.config.RetentionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates compression state transitions for multiple octrees at the scene level.
 *
 * <p>Manages SVO-to-DAG compression with:
 * <ul>
 * <li>Per-octree compression state tracking</li>
 * <li>Retention policy enforcement (DISCARD/RETAIN)</li>
 * <li>Scene-wide compression operations</li>
 * <li>Configuration updates</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * var coordinator = new ESVOCompressionCoordinator(config);
 *
 * // Register octrees
 * coordinator.registerOctree("terrain", terrainSVO);
 * coordinator.registerOctree("buildings", buildingsSVO);
 *
 * // Compress entire scene
 * coordinator.compressScene(sceneOctrees);
 *
 * // Get compressed data
 * var terrain = coordinator.getOctreeData("terrain");
 * }</pre>
 *
 * @author hal.hildebrand
 * @see CompressionConfiguration
 * @see CompressibleOctreeData
 */
public class ESVOCompressionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ESVOCompressionCoordinator.class);

    private CompressionConfiguration config;
    private final DAGPipelineAdapter adapter;

    // State tracking
    private final Map<String, CompressibleOctreeData> compressedCache = new ConcurrentHashMap<>();
    private final Map<String, ESVOOctreeData> originalCache = new ConcurrentHashMap<>();
    private final Map<String, CompressionStatus> statusMap = new ConcurrentHashMap<>();

    /**
     * Create coordinator with the specified configuration.
     *
     * @param config compression configuration
     */
    public ESVOCompressionCoordinator(CompressionConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        this.config = config;
        this.adapter = new DAGPipelineAdapter(config);
    }

    /**
     * Register an octree for tracking.
     *
     * @param name octree name
     * @param octree octree data
     */
    public void registerOctree(String name, ESVOOctreeData octree) {
        originalCache.put(name, octree);
        statusMap.put(name, CompressionStatus.ORIGINAL);
    }

    /**
     * Compress all octrees in the scene.
     *
     * @param scene map of octree names to octree data
     */
    public void compressScene(Map<String, ESVOOctreeData> scene) {
        for (var entry : scene.entrySet()) {
            compressOctree(scene, entry.getKey());
        }
    }

    /**
     * Compress a specific octree.
     *
     * @param scene scene containing the octree
     * @param octreeName name of octree to compress
     * @throws IllegalArgumentException if octree doesn't exist in scene
     */
    public void compressOctree(Map<String, ESVOOctreeData> scene, String octreeName) {
        var octree = scene.get(octreeName);
        if (octree == null) {
            throw new IllegalArgumentException("Octree not found: " + octreeName);
        }

        // Register all untracked scene octrees as ORIGINAL
        for (var entry : scene.entrySet()) {
            if (!statusMap.containsKey(entry.getKey())) {
                originalCache.put(entry.getKey(), entry.getValue());
                statusMap.put(entry.getKey(), CompressionStatus.ORIGINAL);
            }
        }

        // Check if already compressed
        var currentStatus = statusMap.getOrDefault(octreeName, CompressionStatus.PENDING);
        if (currentStatus == CompressionStatus.COMPRESSED) {
            log.debug("Octree {} already compressed, skipping", octreeName);
            return;
        }

        // Perform compression
        var compressed = adapter.compress(octree);

        // Store based on retention policy
        compressedCache.put(octreeName, compressed);
        statusMap.put(octreeName, CompressionStatus.COMPRESSED);

        if (config.retentionPolicy() == RetentionPolicy.RETAIN) {
            originalCache.put(octreeName, octree);
        } else if (config.retentionPolicy() == RetentionPolicy.DISCARD) {
            originalCache.remove(octreeName);
        }

        log.info("Compressed octree: {}", octreeName);
    }

    /**
     * Get octree data (compressed or original).
     *
     * @param name octree name
     * @return octree data, or null if not found
     */
    public CompressibleOctreeData getOctreeData(String name) {
        // Return compressed if available
        var compressed = compressedCache.get(name);
        if (compressed != null) {
            return compressed;
        }

        // Return original if available (wrapped as CompressibleOctreeData)
        var original = originalCache.get(name);
        if (original != null) {
            return (CompressibleOctreeData) original;
        }

        return null;
    }

    /**
     * Decompress all octrees back to original SVO format.
     *
     * @throws IllegalStateException if retention policy is DISCARD
     */
    public void decompressAll() {
        if (config.retentionPolicy() == RetentionPolicy.DISCARD) {
            throw new IllegalStateException(
                "Cannot decompress with DISCARD retention policy - original data was freed");
        }

        // Restore all to original
        for (var name : compressedCache.keySet()) {
            var original = originalCache.get(name);
            if (original != null) {
                statusMap.put(name, CompressionStatus.ORIGINAL);
            }
        }

        compressedCache.clear();
        log.info("Decompressed all octrees");
    }

    /**
     * Get compression status for an octree.
     *
     * @param octreeName octree name
     * @return compression status
     */
    public CompressionStatus getStatus(String octreeName) {
        return statusMap.getOrDefault(octreeName, CompressionStatus.PENDING);
    }

    /**
     * Update the compression configuration.
     *
     * <p>Note: Does not affect already-compressed octrees.
     *
     * @param newConfig new configuration
     */
    public void setCompressionConfiguration(CompressionConfiguration newConfig) {
        if (newConfig == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        this.config = newConfig;
        log.info("Updated compression configuration");
    }

    /**
     * Clear all state.
     *
     * <p>Removes all cached compressed and original data, resets all status to PENDING.
     */
    public void clear() {
        compressedCache.clear();
        originalCache.clear();
        statusMap.clear();
        log.info("Cleared all compression state");
    }

    /**
     * Get compression statistics.
     *
     * @return statistics about compression state
     */
    public CompressionStatistics getCompressionStatistics() {
        var compressed = 0;
        var original = 0;

        for (var status : statusMap.values()) {
            switch (status) {
                case COMPRESSED -> compressed++;
                case ORIGINAL -> original++;
                default -> {
                    // PENDING - don't count
                }
            }
        }

        return new CompressionStatistics(compressed, original, statusMap.size());
    }
}
