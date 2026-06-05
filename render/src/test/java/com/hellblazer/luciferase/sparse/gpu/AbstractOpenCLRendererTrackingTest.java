package com.hellblazer.luciferase.sparse.gpu;

import com.hellblazer.luciferase.sparse.core.SparseVoxelData;
import com.hellblazer.luciferase.sparse.core.SparseVoxelNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the raw-handle tracking bookkeeping in {@link AbstractOpenCLRenderer}.
 *
 * <p>These tests exercise the base-class tracking data structures WITHOUT loading
 * native LWJGL libraries or requiring a real OpenCL context. The test subclass
 * uses the package-private {@code (frameWidth, frameHeight, context)} constructor
 * with a {@code null} context and overrides {@code dispose()} to avoid any native
 * calls — only the pure-Java bookkeeping logic is exercised.
 *
 * <p>Handles are injected via the package-private {@code recordRawHandle(long)} helper
 * (fake longs, no real cl_mem allocation).
 *
 * <p>What is being tested:
 * <ul>
 *   <li>{@code recordRawHandle} adds to the tracking list</li>
 *   <li>{@code untrackRawHandle} removes a specific entry from the tracking list</li>
 *   <li>Simulated dispose clears the tracking list after processing each handle</li>
 *   <li>Simulated dispose is idempotent — second call is a no-op</li>
 *   <li>Untracking before dispose prevents that handle from appearing in the loop</li>
 * </ul>
 */
class AbstractOpenCLRendererTrackingTest {

    private StubRenderer renderer;

    @BeforeEach
    void setUp() {
        // null context: bypasses OpenCLContext.getInstance() — no native library load
        renderer = new StubRenderer(64, 64);
    }

    @Test
    void noHandlesInitially() {
        assertEquals(0, renderer.trackedRawHandleCount(),
                "No handles should be tracked at construction");
    }

    @Test
    void recordRawHandleAddsHandle() {
        renderer.recordRawHandle(1000L);

        assertEquals(1, renderer.trackedRawHandleCount());
        assertTrue(renderer.isTracked(1000L));
    }

    @Test
    void recordMultipleHandles() {
        renderer.recordRawHandle(1L);
        renderer.recordRawHandle(2L);
        renderer.recordRawHandle(3L);

        assertEquals(3, renderer.trackedRawHandleCount());
        assertTrue(renderer.isTracked(1L));
        assertTrue(renderer.isTracked(2L));
        assertTrue(renderer.isTracked(3L));
    }

    @Test
    void untrackRawHandleRemovesSpecificHandle() {
        renderer.recordRawHandle(10L);
        renderer.recordRawHandle(20L);

        renderer.untrackRawHandle(10L);

        assertEquals(1, renderer.trackedRawHandleCount());
        assertFalse(renderer.isTracked(10L), "Untracked handle must not be in the list");
        assertTrue(renderer.isTracked(20L),  "Other handle must remain tracked");
    }

    @Test
    void untrackNonExistentHandleIsHarmless() {
        renderer.recordRawHandle(5L);

        assertDoesNotThrow(() -> renderer.untrackRawHandle(999L),
                "Untracking an unknown handle must not throw");
        assertEquals(1, renderer.trackedRawHandleCount(),
                "Existing handle must not be affected");
    }

    @Test
    void disposeCallsDisposeTypeSpecificAndClearsTracking() {
        renderer.recordRawHandle(42L);
        renderer.recordRawHandle(43L);

        renderer.dispose();

        assertTrue(renderer.disposeTypeSpecificCalled,
                "disposeTypeSpecificBuffers() must be called during dispose()");
        assertEquals(0, renderer.trackedRawHandleCount(),
                "Tracking list must be cleared after dispose()");
    }

    @Test
    void disposeIsIdempotent() {
        renderer.recordRawHandle(77L);

        renderer.dispose();
        int callsAfterFirst = renderer.disposeTypeSpecificCallCount;
        assertEquals(0, renderer.trackedRawHandleCount());

        // Second dispose() must be a no-op
        renderer.dispose();
        assertEquals(callsAfterFirst, renderer.disposeTypeSpecificCallCount,
                "disposeTypeSpecificBuffers() must not be called on second dispose()");
        assertEquals(0, renderer.trackedRawHandleCount());
    }

    @Test
    void untrackBeforeDisposeExcludesHandleFromDisposeLoop() {
        renderer.recordRawHandle(55L);
        renderer.recordRawHandle(66L);

        // Subclass released handle 55 itself, removes it from tracking
        renderer.untrackRawHandle(55L);

        renderer.dispose();

        assertEquals(0, renderer.trackedRawHandleCount(),
                "Tracking list must be empty after dispose()");
        // The dispose loop should have seen exactly 1 handle (66L only; 55L was untracked)
        assertEquals(1, renderer.disposedHandleCount,
                "dispose() should have processed exactly 1 handle — the untracked handle must not appear");
    }

    // -----------------------------------------------------------------------
    // Test double
    // -----------------------------------------------------------------------

    /**
     * Minimal concrete subclass for bookkeeping tests.
     *
     * <p>Uses the package-private {@code null}-context constructor to avoid any native
     * library loading. Overrides {@code dispose()} entirely to exercise only the
     * pure-Java tracking logic: calls {@code disposeTypeSpecificBuffers()}, snapshots
     * the handle count, removes all tracked handles via {@code untrackRawHandle}, and
     * sets the {@code disposed} flag — mirroring the sequence of the real dispose()
     * without any native cl_mem or OpenCL context calls.
     */
    private static class StubRenderer
            extends AbstractOpenCLRenderer<SparseVoxelNode, SparseVoxelData<SparseVoxelNode>> {

        boolean disposeTypeSpecificCalled    = false;
        int     disposeTypeSpecificCallCount = 0;
        int     disposedHandleCount          = 0;

        /** Parallel list tracking insertion order so we can drain the tracking list. */
        private final java.util.List<Long> insertionOrder = new java.util.ArrayList<>();

        StubRenderer(int w, int h) {
            super(w, h, null); // package-private ctor; null context = no native library load
        }

        // Override recordRawHandle to keep our insertion-order list in sync
        @Override
        protected void recordRawHandle(long clMem) {
            super.recordRawHandle(clMem);
            insertionOrder.add(clMem);
        }

        // Override untrackRawHandle to keep insertion-order list in sync
        @Override
        protected void untrackRawHandle(long clMem) {
            super.untrackRawHandle(clMem);
            insertionOrder.removeIf(h -> h == clMem);
        }

        /**
         * Pure-Java dispose() that exercises the bookkeeping path without native calls:
         * 1. idempotent guard
         * 2. disposeTypeSpecificBuffers()
         * 3. snapshot + drain tracked handles
         * 4. set disposed
         */
        @Override
        public void dispose() {
            if (disposed) {
                return;
            }
            disposeTypeSpecificBuffers();

            // Snapshot count, then drain tracked handles (no native call)
            disposedHandleCount = trackedRawHandleCount();
            for (Long handle : new java.util.ArrayList<>(insertionOrder)) {
                untrackRawHandle(handle);
            }

            disposed     = true;
            initialized  = false;
        }

        @Override
        protected void disposeTypeSpecificBuffers() {
            disposeTypeSpecificCalled = true;
            disposeTypeSpecificCallCount++;
        }

        @Override
        protected String getRendererName() {
            return "StubRenderer";
        }

        @Override
        protected String getKernelSource() {
            return "";
        }

        @Override
        protected String getKernelEntryPoint() {
            return "stub";
        }

        @Override
        protected boolean hasDataUploaded() {
            return false;
        }

        @Override
        protected void allocateTypeSpecificBuffers() {
        }

        @Override
        protected void uploadDataBuffers(SparseVoxelData<SparseVoxelNode> data) {
        }

        @Override
        protected void setKernelArguments() {
        }

        @Override
        protected void readTypeSpecificResults() {
        }

        @Override
        protected int computePixelColor(float hitX, float hitY, float hitZ,
                                        float distance, float[] extraData) {
            return 0;
        }
    }
}
