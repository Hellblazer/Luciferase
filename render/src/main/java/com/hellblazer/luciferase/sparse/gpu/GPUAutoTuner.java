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
package com.hellblazer.luciferase.sparse.gpu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GPU Auto-Tuner
 *
 * Stream B Phase 5: Workgroup Auto-Tuning
 * Selects optimal workgroup configuration for a GPU device
 *
 * @author hal.hildebrand
 */
public class GPUAutoTuner {
    private static final Logger log = LoggerFactory.getLogger(GPUAutoTuner.class);

    private final GPUCapabilities capabilities;
    private final String cacheDirectory;
    private final ObjectMapper objectMapper;

    /**
     * Create auto-tuner for a GPU device
     *
     * @param capabilities GPU hardware capabilities
     * @param cacheDirectory directory for caching tuning results
     */
    public GPUAutoTuner(GPUCapabilities capabilities, String cacheDirectory) {
        this.capabilities = capabilities;
        this.cacheDirectory = cacheDirectory;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate candidate configurations to evaluate
     *
     * Candidates based on vendor preferences:
     * - NVIDIA: {32, 64, 128} × {16, 24}
     * - AMD: {64, 128} × {16, 24}
     * - Intel: {32, 64} × {16, 24}
     * - Apple: {32, 64} × {16, 24}
     *
     * @return list of candidate configurations
     */
    public List<WorkgroupConfig> generateCandidates() {
        var candidates = new ArrayList<WorkgroupConfig>();

        // Get vendor-specific workgroup sizes and depths
        var workgroupSizes = getVendorWorkgroupSizes();
        var depths = getVendorDepths();

        // Generate all combinations
        for (var size : workgroupSizes) {
            for (var depth : depths) {
                var config = WorkgroupConfig.withParameters(size, depth, capabilities);
                candidates.add(config);
            }
        }

        log.debug("Generated {} candidates for {} {}", candidates.size(),
                 capabilities.vendor().getDisplayName(), capabilities.model());

        return candidates;
    }

    /**
     * Select optimal configuration from candidate profiles
     *
     * Selection criteria:
     * 1. Highest expected throughput
     * 2. Occupancy >= 30%
     * 3. Valid workgroup size and depth
     *
     * @return optimal configuration
     */
    public WorkgroupConfig selectOptimalConfigFromProfiles() {
        var candidates = generateCandidates();

        // Filter candidates with reasonable occupancy (>= 30%)
        var validCandidates = candidates.stream()
            .filter(c -> c.expectedOccupancy() >= 0.30f)
            .toList();

        // If no candidates meet threshold, use all candidates
        if (validCandidates.isEmpty()) {
            log.warn("No candidates achieved 30% occupancy, using all candidates");
            validCandidates = candidates;
        }

        // Select highest throughput
        var selected = validCandidates.stream()
            .max((a, b) -> Float.compare(a.expectedThroughput(), b.expectedThroughput()))
            .orElseGet(() -> WorkgroupConfig.forDevice(capabilities));

        log.info("Selected config: {} threads, depth {}, occupancy {}%",
                selected.workgroupSize(), selected.maxTraversalDepth(),
                String.format("%.1f", selected.expectedOccupancy() * 100));

        return selected;
    }

    /**
     * Cache configuration to disk for future use
     *
     * @param config configuration to cache
     */
    public void cacheConfiguration(WorkgroupConfig config) {
        try {
            // Ensure cache directory exists
            var cacheDir = Paths.get(cacheDirectory);
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }

            // Write config as JSON
            var cacheFile = cacheDir.resolve(getCacheFileName(capabilities));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), config);

            log.debug("Cached configuration to: {}", cacheFile);
        } catch (IOException e) {
            log.warn("Failed to cache configuration: {}", e.getMessage());
        }
    }

    /**
     * Load configuration from cache
     *
     * @return cached configuration, or empty if not found
     */
    public Optional<WorkgroupConfig> loadFromCache() {
        try {
            var cacheFile = Paths.get(cacheDirectory).resolve(getCacheFileName(capabilities));

            if (!Files.exists(cacheFile)) {
                log.debug("Cache file not found: {}", cacheFile);
                return Optional.empty();
            }

            var config = objectMapper.readValue(cacheFile.toFile(), WorkgroupConfig.class);
            log.info("Loaded cached config: {} threads, depth {}", config.workgroupSize(),
                    config.maxTraversalDepth());

            return Optional.of(config);
        } catch (IOException e) {
            log.warn("Failed to load cache: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get cache filename for a GPU
     *
     * Format: gpu-tuning-{vendor}-{model}.json
     * Example: gpu-tuning-nvidia-rtx4090.json
     *
     * @param caps GPU capabilities
     * @return cache filename
     */
    public static String getCacheFileName(GPUCapabilities caps) {
        // Sanitize model name (remove spaces, special chars)
        var sanitizedModel = caps.model()
            .toLowerCase()
            .replaceAll("[^a-z0-9]", "");

        return String.format("gpu-tuning-%s-%s.json",
                           caps.vendor().name().toLowerCase(),
                           sanitizedModel);
    }

    /**
     * Get vendor-specific workgroup sizes to test
     */
    private int[] getVendorWorkgroupSizes() {
        return switch (capabilities.vendor()) {
            case NVIDIA -> new int[]{32, 64, 128};
            case AMD -> new int[]{64, 128};
            case INTEL -> new int[]{32, 64};
            case APPLE -> new int[]{32, 64};
            case UNKNOWN -> new int[]{32, 64, 128};
        };
    }

    /**
     * Get vendor-specific stack depths to test
     */
    private int[] getVendorDepths() {
        return switch (capabilities.vendor()) {
            case NVIDIA -> new int[]{16, 24};
            case AMD -> new int[]{16, 24};
            case INTEL -> new int[]{16, 24};
            case APPLE -> new int[]{16, 24}; // Apple can handle deeper stacks
            case UNKNOWN -> new int[]{16};
        };
    }
}
