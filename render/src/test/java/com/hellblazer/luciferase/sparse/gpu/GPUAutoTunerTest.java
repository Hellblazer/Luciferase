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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: Tests for GPUAutoTuner
 *
 * Stream B Phase 5: GPU Auto-Tuner
 * Tests runtime benchmarking and configuration selection
 *
 * @author hal.hildebrand
 */
@DisplayName("GPUAutoTuner Tests")
class GPUAutoTunerTest {

    @TempDir
    Path tempDir;

    private GPUCapabilities testCaps;
    private GPUAutoTuner autoTuner;

    @BeforeEach
    void setUp() {
        // Test with NVIDIA RTX 4090 capabilities
        testCaps = new GPUCapabilities(128, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        autoTuner = new GPUAutoTuner(testCaps, tempDir.toString());
    }

    @Test
    @DisplayName("Generate candidate configurations for NVIDIA")
    void testGenerateCandidatesNvidia() {
        // TDD: NVIDIA should get {32, 64, 128} × {16, 24} combinations
        var candidates = autoTuner.generateCandidates();

        assertNotNull(candidates, "Candidates should not be null");
        assertFalse(candidates.isEmpty(), "Should generate at least 1 candidate");

        // Should have 3-6 candidates (multiple sizes and depths)
        assertTrue(candidates.size() >= 3 && candidates.size() <= 6,
                  "NVIDIA should have 3-6 candidates, got: " + candidates.size());

        // All candidates should have valid parameters
        for (var config : candidates) {
            assertTrue(config.isValidWorkgroupSize(),
                      "Candidate workgroup size should be valid: " + config.workgroupSize());
            assertTrue(config.isValidDepth(),
                      "Candidate depth should be valid: " + config.maxTraversalDepth());
        }
    }

    @Test
    @DisplayName("Generate candidate configurations for AMD")
    void testGenerateCandidatesAmd() {
        // TDD: AMD should prefer {64, 128} × {16, 24}
        var amdCaps = new GPUCapabilities(80, 65536, 65536, GPUVendor.AMD, "RX 6900 XT", 64);
        var amdTuner = new GPUAutoTuner(amdCaps, tempDir.toString());

        var candidates = amdTuner.generateCandidates();

        assertFalse(candidates.isEmpty(), "AMD should generate candidates");

        // AMD should prefer larger workgroup sizes (wavefront 64)
        var anyLargeWorkgroup = candidates.stream()
            .anyMatch(c -> c.workgroupSize() >= 64);
        assertTrue(anyLargeWorkgroup, "AMD should include workgroup size >= 64");
    }

    @Test
    @DisplayName("Select optimal configuration from candidates")
    void testSelectOptimalConfig() {
        // TDD: Should select the highest-throughput config
        var selected = autoTuner.selectOptimalConfigFromProfiles();

        assertNotNull(selected, "Should select a configuration");
        assertTrue(selected.isValidWorkgroupSize(), "Selected config should be valid");
        assertTrue(selected.expectedThroughput() > 0.0f, "Should have positive throughput estimate");
    }

    @Test
    @DisplayName("Cache configuration to disk")
    void testCacheConfiguration() throws IOException {
        // TDD: Should save config to cache directory
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test config");

        autoTuner.cacheConfiguration(config);

        // Verify cache file exists
        var cacheFile = tempDir.resolve(GPUAutoTuner.getCacheFileName(testCaps));
        assertTrue(Files.exists(cacheFile), "Cache file should exist at: " + cacheFile);

        // Verify cache file has content
        var content = Files.readString(cacheFile);
        assertFalse(content.isEmpty(), "Cache file should have content");
        assertTrue(content.contains("64"), "Cache should contain workgroup size");
        assertTrue(content.contains("16"), "Cache should contain stack depth");
    }

    @Test
    @DisplayName("Load configuration from cache")
    void testLoadFromCache() throws IOException {
        // TDD: Should load previously cached config
        var originalConfig = new WorkgroupConfig(128, 24, 0.80f, 4.2f, "cached config");

        // Cache it first
        autoTuner.cacheConfiguration(originalConfig);

        // Load it back
        var loaded = autoTuner.loadFromCache();

        assertTrue(loaded.isPresent(), "Should load cached config");
        assertEquals(originalConfig.workgroupSize(), loaded.get().workgroupSize(),
                    "Workgroup size should match");
        assertEquals(originalConfig.maxTraversalDepth(), loaded.get().maxTraversalDepth(),
                    "Stack depth should match");
    }

    @Test
    @DisplayName("Return empty when cache doesn't exist")
    void testLoadFromCacheMissing() {
        // TDD: Should return empty Optional when no cache exists
        var loaded = autoTuner.loadFromCache();

        assertTrue(loaded.isEmpty(), "Should return empty Optional when cache missing");
    }

    @Test
    @DisplayName("Cache file name includes GPU model and vendor")
    void testCacheFileNameFormat() {
        // TDD: Cache filename should identify GPU uniquely
        var fileName = GPUAutoTuner.getCacheFileName(testCaps);

        assertNotNull(fileName, "Cache filename should not be null");
        assertTrue(fileName.contains("NVIDIA") || fileName.contains("nvidia"),
                  "Filename should contain vendor");
        assertTrue(fileName.contains("RTX") || fileName.contains("4090") || fileName.contains("rtx"),
                  "Filename should contain model identifier");
        assertTrue(fileName.endsWith(".json"), "Filename should be JSON");
    }

    @Test
    @DisplayName("Generate multiple candidates with different depths")
    void testCandidatesVaryDepth() {
        // TDD: Candidates should include multiple stack depths
        var candidates = autoTuner.generateCandidates();

        // Count unique depths
        var uniqueDepths = candidates.stream()
            .map(WorkgroupConfig::maxTraversalDepth)
            .distinct()
            .count();

        assertTrue(uniqueDepths >= 2,
                  "Should have at least 2 different depths, got: " + uniqueDepths);
    }

    @Test
    @DisplayName("Generate multiple candidates with different workgroup sizes")
    void testCandidatesVarySize() {
        // TDD: Candidates should include multiple workgroup sizes
        var candidates = autoTuner.generateCandidates();

        // Count unique sizes
        var uniqueSizes = candidates.stream()
            .map(WorkgroupConfig::workgroupSize)
            .distinct()
            .count();

        assertTrue(uniqueSizes >= 2,
                  "Should have at least 2 different workgroup sizes, got: " + uniqueSizes);
    }

    @Test
    @DisplayName("Select config with OccupancyCalculator recommendations")
    void testSelectUsesOccupancyCalculator() {
        // TDD: Selection should consider occupancy estimates
        var selected = autoTuner.selectOptimalConfigFromProfiles();

        // Verify selected config has reasonable occupancy
        var actualOccupancy = OccupancyCalculator.calculateOccupancy(
            testCaps, selected.workgroupSize(), selected.maxTraversalDepth(), 64
        );

        assertTrue(actualOccupancy >= 0.30,
                  "Selected config should achieve at least 30% occupancy, got: " + actualOccupancy);
    }

    @Test
    @DisplayName("Cache preserves configuration metadata")
    void testCachePreservesMetadata() throws IOException {
        // TDD: Cached config should preserve all fields
        var originalConfig = new WorkgroupConfig(
            96, 20, 0.70f, 3.1f, "Optimized for RTX 4090 - balanced"
        );

        autoTuner.cacheConfiguration(originalConfig);
        var loaded = autoTuner.loadFromCache();

        assertTrue(loaded.isPresent());
        var loadedConfig = loaded.get();

        assertEquals(originalConfig.workgroupSize(), loadedConfig.workgroupSize());
        assertEquals(originalConfig.maxTraversalDepth(), loadedConfig.maxTraversalDepth());
        assertEquals(originalConfig.expectedOccupancy(), loadedConfig.expectedOccupancy(), 0.01f);
        assertEquals(originalConfig.expectedThroughput(), loadedConfig.expectedThroughput(), 0.01f);
        assertNotNull(loadedConfig.notes());
    }

    @Test
    @DisplayName("Auto-tuner handles missing cache directory gracefully")
    void testMissingCacheDirectory() {
        // TDD: Should handle non-existent cache directory
        var nonExistentPath = tempDir.resolve("nonexistent");
        var tuner = new GPUAutoTuner(testCaps, nonExistentPath.toString());

        // Should not throw, should return empty
        var loaded = tuner.loadFromCache();
        assertTrue(loaded.isEmpty(), "Should return empty for non-existent cache dir");

        // Should create directory when caching
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");
        assertDoesNotThrow(() -> tuner.cacheConfiguration(config),
                          "Should create cache directory if missing");
    }

    @Test
    @DisplayName("Intel GPU generates appropriate candidates")
    void testCandidatesForIntel() {
        // TDD: Intel should use smaller workgroup sizes
        var intelCaps = new GPUCapabilities(32, 65536, 65536, GPUVendor.INTEL, "Arc A770", 32);
        var intelTuner = new GPUAutoTuner(intelCaps, tempDir.toString());

        var candidates = intelTuner.generateCandidates();

        assertFalse(candidates.isEmpty(), "Intel should generate candidates");

        // Intel should include small-to-medium workgroup sizes
        var hasSmallSize = candidates.stream()
            .anyMatch(c -> c.workgroupSize() <= 64);
        assertTrue(hasSmallSize, "Intel should include workgroup size <= 64");
    }

    @Test
    @DisplayName("Apple GPU generates appropriate candidates")
    void testCandidatesForApple() {
        // TDD: Apple should prefer smaller workgroups, deeper stacks
        var appleCaps = new GPUCapabilities(10, 32768, 65536, GPUVendor.APPLE, "M2 Max", 32);
        var appleTuner = new GPUAutoTuner(appleCaps, tempDir.toString());

        var candidates = appleTuner.generateCandidates();

        assertFalse(candidates.isEmpty(), "Apple should generate candidates");

        // Apple has less local memory, should use smaller workgroups
        var avgSize = candidates.stream()
            .mapToInt(WorkgroupConfig::workgroupSize)
            .average()
            .orElse(0.0);

        assertTrue(avgSize <= 128, "Apple should average <= 128 threads, got: " + avgSize);
    }
}
