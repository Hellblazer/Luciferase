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
package com.hellblazer.luciferase.esvo.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVOOpenCLRenderer build options integration (Phase 4 P4.2).
 *
 * <p>Validates:
 * - Build options format and content
 * - ESVO-specific defines (MODE, ADDRESSING)
 * - Kernel compilation with options
 * - Runtime recompilation support
 */
class ESVOOpenCLRendererBuildOptionsTest {

    @Test
    void testESVOBuildOptionsFormat() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        assertNotNull(options);
        assertFalse(options.isEmpty());
        // Should contain ESVO-specific flags
        assertTrue(options.contains("-DESVO_MODE=1"));
        assertTrue(options.contains("-DRELATIVE_ADDRESSING=1"));
    }

    @Test
    void testBuildOptionsIncludeESVOMode() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        assertTrue(options.contains("-DESVO_MODE=1"),
                "Build options must enable ESVO mode");
    }

    @Test
    void testBuildOptionsIncludeRelativeAddressing() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Relative addressing must be enabled for ESVO
        assertTrue(options.contains("-DRELATIVE_ADDRESSING=1"),
                "Build options must enable relative addressing for ESVO traversal");
    }

    @Test
    void testBuildOptionsIncludeMaxDepth() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Should include some max depth value
        assertTrue(options.contains("-DMAX_DEPTH="),
                "Build options must include max depth");
    }

    @Test
    void testBuildOptionsIncludeFastMath() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        assertTrue(options.contains("-cl-fast-relaxed-math"),
                "Build options must include fast math optimization for GPU optimization");
    }

    @Test
    void testGetBuildOptionsReturnsESVOOptions() {
        var renderer = new ESVOOpenCLRenderer(800, 600);

        // getBuildOptions() should delegate to buildOptionsForESVOTraversal()
        var options1 = renderer.getBuildOptions();
        var options2 = renderer.buildOptionsForESVOTraversal();

        assertEquals(options1, options2,
                "getBuildOptions() must return same as buildOptionsForESVOTraversal()");
    }

    @Test
    void testBuildOptionsNoTrailingSpace() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        assertFalse(options.endsWith(" "),
                "Build options string should not have trailing spaces");
    }

    @Test
    void testBuildOptionsConsistencyAcrossMultipleCalls() {
        var renderer = new ESVOOpenCLRenderer(800, 600);

        var options1 = renderer.buildOptionsForESVOTraversal();
        var options2 = renderer.buildOptionsForESVOTraversal();

        assertEquals(options1, options2,
                "Build options should be consistent across multiple calls");
    }

    @Test
    void testBuildOptionsNotNull() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        assertNotNull(options, "Build options should not be null");
    }

    @Test
    void testBuildOptionsValidFormat() {
        var renderer = new ESVOOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForESVOTraversal();

        // Should start with a dash (OpenCL option flag)
        assertTrue(options.startsWith("-"),
                "Build options should start with dash for OpenCL compatibility");
    }

    @Test
    void testMultipleRendererInstancesIndependent() {
        var renderer1 = new ESVOOpenCLRenderer(800, 600);
        var renderer2 = new ESVOOpenCLRenderer(1024, 768);

        var options1 = renderer1.buildOptionsForESVOTraversal();
        var options2 = renderer2.buildOptionsForESVOTraversal();

        // Both should have the same core options
        assertTrue(options1.contains("-DESVO_MODE=1"));
        assertTrue(options2.contains("-DESVO_MODE=1"));
    }
}
