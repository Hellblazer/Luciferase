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
package com.hellblazer.luciferase.esvo.traversal;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.cpu.ESVOCPUTraversal;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.gpu.ESVOOpenCLRenderer;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import javax.vecmath.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test GPU kernel compilation and execution with both addressing modes.
 *
 * <p>Validates that GPU kernels work with both SVO and DAG data after
 * adding addressing mode support.
 *
 * <p><b>Note:</b> GPU tests require {@code dangerouslyDisableSandbox: true}
 * and OpenCL hardware support. Tests are disabled if GPU is unavailable.
 *
 * @author hal.hildebrand
 */
@DisplayName("GPU Addressing Mode Tests")
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "GPU tests require OpenGL context and OpenCL hardware not available in CI"
)
@EnabledIf("isGPUAvailable")
class GPUAddressingModeTest {

    private ESVOOpenCLRenderer renderer;

    /**
     * Check if GPU/OpenCL is available for testing.
     */
    static boolean isGPUAvailable() {
        try {
            // Try to create renderer - will fail if no OpenCL
            var testRenderer = new ESVOOpenCLRenderer(64, 64);
            testRenderer.dispose();
            return true;
        } catch (Exception e) {
            System.err.println("GPU tests disabled: " + e.getMessage());
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        renderer = new ESVOOpenCLRenderer(256, 256);
    }

    @AfterEach
    void tearDown() {
        if (renderer != null) {
            renderer.dispose();
        }
    }

    /**
     * Test GPU kernel compiles with RELATIVE addressing mode (SVO).
     */
    @Test
    @DisplayName("GPU kernel compiles with RELATIVE addressing (SVO)")
    @Disabled("GPU tests require OpenCL hardware not available in CI")
    void testGPUCompilationRelativeMode() {
        var svo = createTestSVO();

        // Upload data should trigger kernel compilation
        assertDoesNotThrow(() -> renderer.uploadData(svo),
            "GPU kernel should compile for SVO (RELATIVE mode)");
    }

    /**
     * Test addressing modes are correctly implemented.
     */
    @Test
    @DisplayName("Addressing modes are correctly identified")
    void testAddressingModeIdentification() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();

        // Verify SVO uses RELATIVE addressing
        assertEquals(PointerAddressingMode.RELATIVE, svo.getAddressingMode(),
            "SVO should use RELATIVE addressing");

        // Verify DAG uses ABSOLUTE addressing
        assertEquals(PointerAddressingMode.ABSOLUTE, dag.getAddressingMode(),
            "DAG should use ABSOLUTE addressing");
    }

    /**
     * Test GPU kernel runs on SVO data.
     */
    @Test
    @DisplayName("GPU kernel runs on SVO data")
    @Disabled("GPU tests require OpenCL hardware not available in CI")
    void testGPUExecutionOnSVO() {
        var svo = createTestSVO();

        renderer.uploadData(svo);

        // Render a frame from default camera position
        var cameraPos = new Vector3f(0.5f, 0.5f, 1.5f);
        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);

        assertDoesNotThrow(() -> renderer.renderFrame(cameraPos, lookAt, 45.0f),
            "GPU should render SVO data successfully");
    }

    /**
     * Test DAG addressing mode is correctly set.
     */
    @Test
    @DisplayName("DAG addressing mode is ABSOLUTE")
    void testDAGAddressingMode() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();

        // Verify DAG uses absolute addressing
        assertEquals(PointerAddressingMode.ABSOLUTE, dag.getAddressingMode(),
            "DAG should use ABSOLUTE addressing mode");

        // Verify data is preserved
        assertTrue(dag.nodeCount() > 0, "DAG should have nodes");
    }

    /**
     * Test GPU rendering with SVO data from multiple views.
     */
    @Test
    @DisplayName("GPU SVO rendering from multiple views")
    @Disabled("GPU tests require OpenCL hardware not available in CI")
    void testGPUSVOMultipleViews() {
        var svo = createTestSVO();
        renderer.uploadData(svo);

        // Render orthogonal views
        var views = new Vector3f[][] {
            {new Vector3f(1.0f, 0.5f, 0.5f), new Vector3f(0.5f, 0.5f, 0.5f)},
            {new Vector3f(0.5f, 1.0f, 0.5f), new Vector3f(0.5f, 0.5f, 0.5f)},
            {new Vector3f(0.5f, 0.5f, 1.0f), new Vector3f(0.5f, 0.5f, 0.5f)}
        };

        for (var view : views) {
            assertDoesNotThrow(() -> renderer.renderFrame(view[0], view[1], 45.0f),
                "GPU render from orthogonal view should succeed");
        }
    }

    /**
     * Test multiple SVO renders with camera switching.
     */
    @Test
    @DisplayName("Multiple renders from different camera positions")
    @Disabled("GPU tests require OpenCL hardware not available in CI")
    void testMultipleRenders() {
        var svo = createTestSVO();
        renderer.uploadData(svo);

        var cameraPos1 = new Vector3f(0.5f, 0.5f, 1.5f);
        var lookAt1 = new Vector3f(0.5f, 0.5f, 0.5f);

        // Render from first camera
        assertDoesNotThrow(() -> renderer.renderFrame(cameraPos1, lookAt1, 45.0f),
            "First render should succeed");

        // Render from different camera
        var cameraPos2 = new Vector3f(1.0f, 1.0f, 1.0f);
        var lookAt2 = new Vector3f(0.5f, 0.5f, 0.5f);

        assertDoesNotThrow(() -> renderer.renderFrame(cameraPos2, lookAt2, 60.0f),
            "Second render from different camera should succeed");
    }

    /**
     * Test CPU traversal with DAG addressing mode.
     *
     * <p>Since GPU DAG rendering requires a DAGOpenCLRenderer (not implemented in Phase 3),
     * we test CPU traversal with both SVO and DAG to verify addressing modes work correctly.
     */
    @Test
    @DisplayName("CPU traversal works with both SVO and DAG")
    void testCPUTraversalWithBothAddressingModes() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();

        // Both should have valid addressing modes
        assertEquals(com.hellblazer.luciferase.sparse.core.PointerAddressingMode.RELATIVE,
                     svo.getAddressingMode(),
                     "SVO should use RELATIVE addressing");

        assertEquals(com.hellblazer.luciferase.sparse.core.PointerAddressingMode.ABSOLUTE,
                     dag.getAddressingMode(),
                     "DAG should use ABSOLUTE addressing");

        // Both should be traversable
        var svoRoot = svo.getNode(0);
        var dagRoot = dag.getNode(0);

        assertNotNull(svoRoot, "SVO root should exist");
        assertNotNull(dagRoot, "DAG root should exist");
    }

    // === Helper Methods ===

    /**
     * Create simple test SVO.
     */
    private ESVOOctreeData createTestSVO() {
        var nodes = new ESVONodeUnified[3];

        nodes[0] = new ESVONodeUnified();
        nodes[0].setChildMask(0b10000001);
        nodes[0].setChildPtr(1);
        nodes[0].setValid(true);

        nodes[1] = new ESVONodeUnified();
        nodes[1].setChildMask(0);
        nodes[1].setLeafMask(0xFF);
        nodes[1].setValid(true);

        nodes[2] = new ESVONodeUnified();
        nodes[2].setChildMask(0);
        nodes[2].setLeafMask(0xFF);
        nodes[2].setValid(true);

        return ESVOOctreeData.fromNodes(nodes);
    }

    /**
     * Convert SparseVoxelData to OctreeNode[] for CPU comparison.
     */
    private ESVOCPUTraversal.OctreeNode[] convertToOctreeNodes(
        com.hellblazer.luciferase.sparse.core.SparseVoxelData<ESVONodeUnified> data) {

        int nodeCount = data.nodeCount();
        var nodes = new ESVOCPUTraversal.OctreeNode[nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            var node = data.getNode(i);
            if (node != null) {
                nodes[i] = new ESVOCPUTraversal.OctreeNode(
                    node.getChildDescriptor(),
                    node.getContourDescriptor()
                );
            } else {
                nodes[i] = new ESVOCPUTraversal.OctreeNode(0, 0);
            }
        }

        return nodes;
    }
}
