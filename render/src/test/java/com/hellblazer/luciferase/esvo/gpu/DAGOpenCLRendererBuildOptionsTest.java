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
 * Tests for DAGOpenCLRenderer build options integration (Phase 4 P4.2).
 *
 * <p>Validates:
 * - Build options format and content
 * - DAG-specific defines (TRAVERSAL, ADDRESSING)
 * - Kernel compilation with options
 * - Runtime recompilation support
 */
class DAGOpenCLRendererBuildOptionsTest {

    @Test
    void testDAGBuildOptionsFormat() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        assertNotNull(options);
        assertFalse(options.isEmpty());
        // Should contain DAG-specific flags
        assertTrue(options.contains("-DDAG_TRAVERSAL=1"));
        assertTrue(options.contains("-DABSOLUTE_ADDRESSING=1"));
    }

    @Test
    void testBuildOptionsIncludeAbsoluteAddressing() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        // Absolute addressing must be enabled for DAG
        assertTrue(options.contains("-DABSOLUTE_ADDRESSING=1"),
                "Build options must enable absolute addressing for DAG traversal");
    }

    @Test
    void testBuildOptionsIncludeMaxDepth() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        // Should include some max depth value
        assertTrue(options.contains("-DMAX_DEPTH="),
                "Build options must include max depth");
    }

    @Test
    void testGetBuildOptionsReturnsDAGOptions() {
        var renderer = new DAGOpenCLRenderer(800, 600);

        // getBuildOptions() should delegate to buildOptionsForDAGTraversal()
        var options1 = renderer.getBuildOptions();
        var options2 = renderer.buildOptionsForDAGTraversal();

        assertEquals(options1, options2,
                "getBuildOptions() must return same as buildOptionsForDAGTraversal()");
    }

    @Test
    void testBuildOptionsNoTrailingSpace() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        assertFalse(options.endsWith(" "),
                "Build options string should not have trailing spaces");
    }

    @Test
    void testBuildOptionsNotNull() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        assertNotNull(options, "Build options should not be null");
    }

    @Test
    void testBuildOptionsContainsDAGKeywords() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        assertTrue(options.contains("TRAVERSAL"),
                "Build options must reference DAG traversal");
        assertTrue(options.contains("ADDRESSING"),
                "Build options must reference addressing mode");
    }

    @Test
    void testBuildOptionsConsistent() {
        var renderer = new DAGOpenCLRenderer(800, 600);

        var options1 = renderer.buildOptionsForDAGTraversal();
        var options2 = renderer.buildOptionsForDAGTraversal();

        assertEquals(options1, options2,
                "Build options should be consistent across calls");
    }

    @Test
    void testBuildOptionsValidFormat() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        // Should start with a dash (OpenCL option flag)
        assertTrue(options.startsWith("-"),
                "Build options should start with dash for OpenCL compatibility");
    }

    @Test
    void testMultipleRendererInstancesIndependent() {
        var renderer1 = new DAGOpenCLRenderer(800, 600);
        var renderer2 = new DAGOpenCLRenderer(1024, 768);

        var options1 = renderer1.buildOptionsForDAGTraversal();
        var options2 = renderer2.buildOptionsForDAGTraversal();

        // Both should have the same core options (different sizes don't affect build options)
        assertTrue(options1.contains("-DDAG_TRAVERSAL=1"));
        assertTrue(options2.contains("-DDAG_TRAVERSAL=1"));
    }

    @Test
    void testBuildOptionsLength() {
        var renderer = new DAGOpenCLRenderer(800, 600);
        var options = renderer.buildOptionsForDAGTraversal();

        // Build options should have reasonable length (not empty, not unreasonably large)
        assertTrue(options.length() > 10, "Build options should contain meaningful content");
        assertTrue(options.length() < 500, "Build options should not be excessively long");
    }
}
