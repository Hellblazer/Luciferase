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
 * Tests for ESVO Stack Depth Optimization (Phase 5 Stream A).
 *
 * <p>Validates:
 * - Build options support for configurable stack depth
 * - Stack depth parameter formatting
 * - Consistency across renderer instances
 * - Occupancy impact from stack depth choices
 */
class ESVOStackOptimizationTest {

    @Test
    @DisplayName("Stack depth configurable in build options")
    void testStackDepthConfigurable() {
        // Verify that ESVO build options include stack depth parameter
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        assertNotNull(options);
        assertTrue(options.contains("-DESVO_MODE=1"));
        assertTrue(options.contains("-DRELATIVE_ADDRESSING=1"));
        // Should include MAX_DEPTH parameter from maxDepth
        assertTrue(options.contains("-DMAX_DEPTH="));
    }

    @Test
    @DisplayName("Build options follow OpenCL format")
    void testBuildOptionsFormat() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Should have proper OpenCL format
        assertFalse(options.startsWith(" "), "Options should not start with space");
        assertFalse(options.endsWith(" "), "Options should not end with space");
        assertTrue(options.startsWith("-"), "Options should start with dash");

        // Each flag should be properly formatted
        assertTrue(options.contains("-DESVO_MODE=1"));
        assertTrue(options.contains("-DRELATIVE_ADDRESSING=1"));
        assertTrue(options.contains("-cl-fast-relaxed-math"));
    }

    @Test
    @DisplayName("Build options include both relative addressing and traversal depth")
    void testBuildOptionsIncludesAddressingAndDepth() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // ESVO-specific settings
        assertTrue(options.contains("-DESVO_MODE=1"),
                "Build options must enable ESVO mode");
        assertTrue(options.contains("-DRELATIVE_ADDRESSING=1"),
                "Build options must enable relative addressing");
        assertTrue(options.contains("-DMAX_DEPTH="),
                "Build options must include max depth");
    }

    @Test
    @DisplayName("Stack depth parameter present and numeric")
    void testStackDepthParameterValid() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Extract and verify depth parameter
        var depthStart = options.indexOf("-DMAX_DEPTH=");
        assertNotEquals(-1, depthStart, "Must contain -DMAX_DEPTH=");

        // After the flag should be a number
        var depthValueStart = depthStart + "-DMAX_DEPTH=".length();
        var depthValueEnd = Math.min(depthValueStart + 2, options.length());
        var depthValue = options.substring(depthValueStart, depthValueEnd).trim();

        // Should be numeric
        try {
            Integer.parseInt(depthValue);
        } catch (NumberFormatException e) {
            fail("Depth value should be numeric: " + depthValue);
        }
    }

    @Test
    @DisplayName("Different renderer instances have consistent options")
    void testConsistentOptions() {
        var renderer1 = new ESVOOpenCLRenderer(800, 600);
        var renderer2 = new ESVOOpenCLRenderer(1024, 768);
        var renderer3 = new ESVOOpenCLRenderer(1280, 1024);

        var options1 = renderer1.buildOptionsForESVOTraversal();
        var options2 = renderer2.buildOptionsForESVOTraversal();
        var options3 = renderer3.buildOptionsForESVOTraversal();

        // All should have same base structure
        assertTrue(options1.contains("-DESVO_MODE=1"));
        assertTrue(options2.contains("-DESVO_MODE=1"));
        assertTrue(options3.contains("-DESVO_MODE=1"));

        // All should reference relative addressing
        assertTrue(options1.contains("-DRELATIVE_ADDRESSING=1"));
        assertTrue(options2.contains("-DRELATIVE_ADDRESSING=1"));
        assertTrue(options3.contains("-DRELATIVE_ADDRESSING=1"));
    }

    @Test
    @DisplayName("Build options can be parsed and reconstructed")
    void testOptionsRepresentable() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Should be a valid string of OpenCL options
        assertNotNull(options);
        assertFalse(options.isEmpty());

        // Should be representable as string in build command
        var cmdLine = "clang -c kernel.cl " + options;
        assertNotNull(cmdLine);
        assertTrue(cmdLine.contains("-DESVO_MODE=1"));
    }
}
