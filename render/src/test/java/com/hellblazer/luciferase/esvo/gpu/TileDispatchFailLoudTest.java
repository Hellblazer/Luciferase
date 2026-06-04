// SPDX-License-Identifier: AGPL-3.0-only
package com.hellblazer.luciferase.esvo.gpu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that enabling the tile-dispatch path via {@code -DENABLE_TILE_DISPATCH}
 * fails loudly rather than silently producing an all-miss (zeroed) frame.
 *
 * <p>No GPU or OpenCL context required: we exercise only the flag-routing logic
 * inside {@link DAGOpenCLRenderer#executeKernel()}.
 *
 * <p>Bead: Luciferase-7wzml.26
 */
class TileDispatchFailLoudTest {

    /**
     * Thin subclass that bypasses OpenCL initialization so the test does not
     * need a GPU, while still exercising the real {@code executeKernel()} guard.
     */
    private static class TestableRenderer extends DAGOpenCLRenderer {
        TestableRenderer() {
            super(64, 64);
        }

        /** Expose protected method for direct testing without a full renderFrame cycle. */
        void callExecuteKernel() throws Exception {
            executeKernel();
        }
    }

    @BeforeEach
    void enableTileDispatch() {
        System.setProperty("ENABLE_TILE_DISPATCH", "true");
    }

    @AfterEach
    void clearTileDispatch() {
        System.clearProperty("ENABLE_TILE_DISPATCH");
    }

    /**
     * With {@code -DENABLE_TILE_DISPATCH} set, {@code executeKernel()} must throw
     * {@link UnsupportedOperationException} — never silently return and never allow
     * a zeroed frame to be produced.
     */
    @Test
    void executeKernel_withTileDispatchEnabled_throwsUnsupportedOperationException() throws Exception {
        var renderer = new TestableRenderer();
        var ex = assertThrows(UnsupportedOperationException.class, renderer::callExecuteKernel,
            "executeKernel() must throw UnsupportedOperationException when tile-dispatch is "
            + "enabled and KernelExecutor is unimplemented — not silently produce an all-miss frame");
        assertTrue(ex.getMessage().contains("ENABLE_TILE_DISPATCH"),
            "Exception message should mention ENABLE_TILE_DISPATCH flag; got: " + ex.getMessage());
    }
}
