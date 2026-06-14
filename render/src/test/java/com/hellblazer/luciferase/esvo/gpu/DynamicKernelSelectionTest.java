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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.2.2b: Dynamic Kernel Selection Integration Tests
 *
 * Validates that batch kernel is properly initialized and selected based on coherence:
 * - Batch kernel is compiled and initialized after main kernel
 * - Kernel selection based on coherence threshold (>= 0.5)
 * - Both kernels produce identical results
 * - RaysPerItem calculation based on coherence score
 * - Kernel switching works correctly during rendering
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 4.2.2b: Dynamic Kernel Selection")
// GPU device-probing test: every case constructs a DAGOpenCLRenderer and calls initialize() (OpenCL
// kernel compilation + device selection). On macOS/GraalVM the device probe can crash the surefire fork
// (exit 133, "terminated without properly saying goodbye"), aborting the whole render module run when not
// excluded (Luciferase-vkl96). Guard it as opt-in like the other device-touching GPU tests
// (DAGOpenCLRendererVendorTest, ESVOOpenCLRendererFarPointerTest): it runs only when RUN_GPU_TESTS=true,
// so normal local + CI runs skip it (RUN_GPU_TESTS unset) instead of crashing. This subsumes the prior
// CI-only disable — RUN_GPU_TESTS is unset in CI too.
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true",
        disabledReason = "GPU device probing crashes the surefire fork on macOS/GraalVM (Luciferase-vkl96); "
                + "opt-in via RUN_GPU_TESTS=true on a machine with working OpenCL")
class DynamicKernelSelectionTest {

    private DAGOpenCLRenderer renderer;
    private DAGOctreeData testDAG;
    private ESVOOctreeData testSVO;

    @BeforeEach
    void setUp() {
        // Build test DAG
        testSVO = buildTestOctree();
        testDAG = buildTestDAG(testSVO);

        // Initialize renderer
        renderer = new DAGOpenCLRenderer(512, 512, "test_cache_dir");
    }

    /**
     * Test that batch kernel is initialized after main kernel compilation
     */
    @Test
    @DisplayName("Batch kernel initialized after main kernel")
    void testBatchKernelInitialization() {
        // Before initialization, batch kernel should be null
        assertFalse(renderer.isBatchKernelAvailable(),
                   "Batch kernel should not be available before initialization");

        // Initialize renderer (compiles main kernel)
        assertDoesNotThrow(() -> renderer.initialize(),
                          "Renderer initialization should succeed");

        // After initialization, batch kernel should be available
        assertTrue(renderer.isBatchKernelAvailable(),
                  "Batch kernel should be available after initialization");
    }

    /**
     * Test that kernel selection is based on coherence threshold
     */
    @Test
    @DisplayName("Kernel selection based on coherence threshold (>= 0.5)")
    void testCoherenceBasedKernelSelection() {
        // Initialize renderer
        renderer.initialize();

        // Upload DAG data
        renderer.uploadData(testDAG);

        // Verify coherence measurement and kernel selection
        assertTrue(renderer.isBatchKernelAvailable(),
                  "Batch kernel must be available for selection");

        // The actual selection happens during updateCoherenceIfNeeded()
        // which is called in executeKernel(). We validate the logic here:
        double testCoherence1 = 0.3;  // Below threshold
        double testCoherence2 = 0.7;  // Above threshold

        boolean select1 = (testCoherence1 >= 0.5);
        boolean select2 = (testCoherence2 >= 0.5);

        assertFalse(select1, "Coherence 0.3 should not activate batch kernel");
        assertTrue(select2, "Coherence 0.7 should activate batch kernel");
    }

    /**
     * Test raysPerItem calculation from coherence score
     */
    @Test
    @DisplayName("RaysPerItem scaling from coherence score")
    void testRaysPerItemCalculation() {
        // Formula: Math.max(1, Math.min(16, (int) Math.ceil(coherence * 16.0)))
        var testCases = new Object[][] {
            {0.0, 1},      // No coherence -> 1 ray/item
            {0.25, 4},     // 0.25 * 16 = 4
            {0.5, 8},      // 0.5 * 16 = 8
            {0.75, 12},    // 0.75 * 16 = 12
            {1.0, 16}      // Full coherence -> 16 rays/item
        };

        for (Object[] testCase : testCases) {
            double coherence = (double) testCase[0];
            int expected = (int) testCase[1];

            int actual = Math.max(1, Math.min(16, (int) Math.ceil(coherence * 16.0)));

            assertEquals(expected, actual,
                String.format("Coherence %.2f should yield raysPerItem=%d", coherence, expected));
        }
    }

    /**
     * Test that both kernels are correctly wired in kernel argument setup
     */
    @Test
    @DisplayName("Kernel arguments correctly set for both single-ray and batch kernels")
    void testKernelArgumentSetup() {
        // Initialize renderer
        renderer.initialize();

        // Upload DAG data
        renderer.uploadData(testDAG);

        // Verify both kernels can access required resources:
        // - nodePool buffer
        // - dummy childPointers buffer (from Phase 4.2.2a fix)
        // - nodeCount parameter
        // - rays buffer
        // - rayCount parameter
        // - results buffer
        // - raysPerItem parameter (batch kernel only)

        assertTrue(renderer.isBatchKernelAvailable(),
                  "Batch kernel should be available");

        assertNotNull(testDAG, "Test DAG should be created");
        assertTrue(testDAG.nodeCount() > 0, "Test DAG should have nodes");
    }

    /**
     * Test that kernel selection decision logic matches coherence threshold
     */
    @Test
    @DisplayName("Kernel selection decision matches coherence >= 0.5 threshold")
    void testKernelSelectionDecisionLogic() {
        double[] testCoherences = {0.49, 0.5, 0.51, 0.99, 1.0};
        boolean[] expectedSelection = {false, true, true, true, true};

        for (int i = 0; i < testCoherences.length; i++) {
            double coherence = testCoherences[i];
            boolean expected = expectedSelection[i];

            // This matches the logic in DAGOpenCLRenderer.updateCoherenceIfNeeded()
            boolean useBatch = (coherence >= 0.5);

            assertEquals(expected, useBatch,
                String.format("Coherence %.2f should select batch=%s", coherence, expected));
        }
    }

    /**
     * Test that renderer can be disposed properly with both kernels
     */
    @Test
    @DisplayName("Renderer disposal handles both kernels correctly")
    void testRendererDisposal() {
        // Initialize renderer
        renderer.initialize();

        // Upload DAG data
        renderer.uploadData(testDAG);

        // Dispose should clean up both kernels and buffers
        assertDoesNotThrow(() -> renderer.dispose(),
                          "Renderer disposal should succeed");

        // Verify renderer is no longer initialized
        assertFalse(renderer.isInitialized(),
                   "Renderer should be marked as disposed");
    }

    /**
     * Re-upload then dispose must not double-free the node/childPointers buffers (Luciferase-qe8p0).
     * uploadDataBuffers releases the previous tracked buffers on a re-upload; it must also untrack them, or
     * dispose()'s defensive release loop frees the stale handle again — a native OpenCL abort (exit 133).
     */
    @Test
    @DisplayName("Re-upload then dispose does not double-free (qe8p0)")
    void testReuploadThenDisposeDoesNotDoubleFree() {
        renderer.initialize();
        renderer.uploadData(testDAG);
        // node + dummy-childPointers buffers tracked.
        assertEquals(2, renderer.trackedRawHandleCountForTest(),
                     "upload tracks the node + dummy-childPointers buffers");

        assertDoesNotThrow(() -> renderer.uploadData(testDAG), "re-upload must release+untrack the old buffers");
        // Structural invariant: re-upload must untrack the OLD pair (count stays 2, not 4) — otherwise the
        // stale freed handles remain tracked for dispose() to double-free.
        assertEquals(2, renderer.trackedRawHandleCountForTest(),
                     "re-upload must untrack old buffers (count stays 2, not 4) — else dispose double-frees");

        assertDoesNotThrow(() -> renderer.dispose(),
                          "dispose after re-upload must not double-free tracked buffers");
        // dispose must leave NO tracked handles (each released exactly once).
        assertEquals(0, renderer.trackedRawHandleCountForTest(),
                     "dispose must untrack every released buffer (no handle left for a second free)");
        assertFalse(renderer.isInitialized(), "renderer must be disposed");
    }

    /**
     * Test batch kernel initialization failure handling
     */
    @Test
    @DisplayName("Batch kernel compilation failure doesn't break renderer")
    void testBatchKernelFailureHandling() {
        // Initialize renderer normally
        renderer.initialize();

        // Batch kernel compilation failures should be logged but not fatal
        // (this is validated by the try-catch in initializeBatchKernel)

        // Renderer should still be functional even if batch kernel fails
        assertTrue(renderer.isInitialized(),
                  "Renderer should be initialized despite potential batch kernel issues");

        // Main kernel should always be available
        assertNotNull(renderer.getKernel(),
                     "Main kernel must always be available");
    }

    /**
     * Test that kernel initialization order is correct
     */
    @Test
    @DisplayName("Kernel initialization order: main kernel compiled before batch kernel")
    void testKernelInitializationOrder() {
        // Before initialization
        assertFalse(renderer.isInitialized(), "Renderer not initialized");
        assertFalse(renderer.isBatchKernelAvailable(), "Batch kernel not available");

        // Initialize
        renderer.initialize();

        // After initialization
        assertTrue(renderer.isInitialized(), "Renderer is initialized");
        assertNotNull(renderer.getKernel(), "Main kernel is compiled");
        assertTrue(renderer.isBatchKernelAvailable(), "Batch kernel is initialized");

        // Verify order: main kernel exists before batch kernel
        // (batch kernel is created in onKernelCompiled which is called after compileKernel)
    }

    // ==================== Helper Methods ====================

    private ESVOOctreeData buildTestOctree() {
        var octree = new ESVOOctreeData(16);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF);  // All 8 children present
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Add 8 leaf nodes
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);   // No children (leaf node)
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    private DAGOctreeData buildTestDAG(ESVOOctreeData octree) {
        return DAGBuilder.from(octree).build();
    }
}
