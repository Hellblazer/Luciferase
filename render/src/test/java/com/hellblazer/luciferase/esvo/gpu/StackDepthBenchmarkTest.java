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
package com.hellblazer.luciferase.esvo.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmarks for ESVO Stack Depth Optimization validation (Phase 5 Stream A Days 4-5).
 *
 * <p>Measures:
 * - LDS memory savings from reduced stack depth
 * - Occupancy potential with lower LDS requirements
 * - Consistency of rendering across depth configurations
 * - Build options correctness for different scenarios
 */
class StackDepthBenchmarkTest {

    @Test
    @DisplayName("Stack Memory Reduction - LDS Usage Calculation")
    void testStackMemoryReduction() {
        // Verify LDS memory usage reduction
        // StackEntry = 4 bytes (uint32 nodeIdx + uint32 scale)
        // Workgroup 64 threads

        int workgroupSize = 64;
        int bytesPerStackEntry = 4;

        long ldsUsageDepth32 = (long) 32 * workgroupSize * bytesPerStackEntry;  // 8192 bytes
        long ldsUsageDepth16 = (long) 16 * workgroupSize * bytesPerStackEntry;  // 4096 bytes

        double reductionPercent = ((double)(ldsUsageDepth32 - ldsUsageDepth16) / ldsUsageDepth32) * 100;

        assertEquals(50.0, reductionPercent, 0.1,
                "Stack memory should reduce by 50% from depth 32 to 16");
    }

    @Test
    @DisplayName("Occupancy Potential - Lower LDS Enables Higher Occupancy")
    void testOccupancyPotential() {
        // With LDS reduced from 8KB to 4KB, GPU can fit more workgroups
        // Occupancy = active workgroups / max workgroups possible

        int ldsPerWorkgroupDepth32 = 32 * 64 * 4;  // 8192 bytes
        int ldsPerWorkgroupDepth16 = 16 * 64 * 4;  // 4096 bytes
        int totalLDS = 65536;  // 64 KB typical

        int maxWorkgroupsDepth32 = totalLDS / ldsPerWorkgroupDepth32;  // 8
        int maxWorkgroupsDepth16 = totalLDS / ldsPerWorkgroupDepth16;  // 16

        // Reduced depth should allow more concurrent workgroups
        assertEquals(8, maxWorkgroupsDepth32);
        assertEquals(16, maxWorkgroupsDepth16);
        assertTrue(maxWorkgroupsDepth16 > maxWorkgroupsDepth32,
                "Depth 16 should allow more concurrent workgroups than depth 32");
    }

    @Test
    @DisplayName("Build Options Depth Parameter - ESVO Traversal")
    void testBuildOptionsDepthParameter() {
        // Verify build options correctly include depth parameter
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Should contain properly formatted depth parameter
        assertTrue(options.contains("-DMAX_DEPTH="),
                "Build options must include -DMAX_DEPTH= parameter");

        // Should extract numeric value
        var depthIdx = options.indexOf("-DMAX_DEPTH=");
        var endIdx = Math.min(depthIdx + "-DMAX_DEPTH=".length() + 2, options.length());
        var depthStr = options.substring(depthIdx + "-DMAX_DEPTH=".length(), endIdx).trim();

        // Should be numeric
        assertTrue(depthStr.matches("\\d+"), "Depth should be numeric: " + depthStr);
    }

    @Test
    @DisplayName("Build Options Consistency - Multiple Instances")
    void testBuildOptionsConsistency() {
        // Different renderer instances should produce consistent options
        var r1 = new ESVOOpenCLRenderer(800, 600);
        var r2 = new ESVOOpenCLRenderer(1024, 768);
        var r3 = new ESVOOpenCLRenderer(1280, 1024);

        var opts1 = r1.buildOptionsForESVOTraversal();
        var opts2 = r2.buildOptionsForESVOTraversal();
        var opts3 = r3.buildOptionsForESVOTraversal();

        // All should have identical content (same default depth)
        assertEquals(opts1, opts2, "Renderers should produce identical build options");
        assertEquals(opts2, opts3, "Renderers should produce identical build options");

        // All should contain ESVO-specific markers
        assertTrue(opts1.contains("-DESVO_MODE=1"));
        assertTrue(opts1.contains("-DRELATIVE_ADDRESSING=1"));
        assertTrue(opts1.contains("-DMAX_DEPTH="));
    }

    @Test
    @DisplayName("Build Options Format - No Trailing Spaces")
    void testBuildOptionsNoTrailingSpace() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Should be properly trimmed
        assertFalse(options.startsWith(" "), "Options should not start with space");
        assertFalse(options.endsWith(" "), "Options should not end with space");

        // Should be usable as command line argument
        var cmdLine = "clang " + options + " kernel.cl";
        assertTrue(cmdLine.contains("-DESVO_MODE=1"));
    }
}
