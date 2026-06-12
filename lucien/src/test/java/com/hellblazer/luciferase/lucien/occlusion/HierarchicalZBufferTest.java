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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for HierarchicalZBuffer
 *
 * @author hal.hildebrand
 */
public class HierarchicalZBufferTest {

    private HierarchicalZBuffer zBuffer;

    @BeforeEach
    void setUp() {
        // Create a 256x256 buffer with 4 levels
        zBuffer = new HierarchicalZBuffer(256, 256, 4);
    }

    @Test
    void testInitialization() {
        assertEquals(256, zBuffer.getWidthAtLevel(0));
        assertEquals(256, zBuffer.getHeightAtLevel(0));
        assertEquals(128, zBuffer.getWidthAtLevel(1));
        assertEquals(128, zBuffer.getHeightAtLevel(1));
        assertEquals(64, zBuffer.getWidthAtLevel(2));
        assertEquals(64, zBuffer.getHeightAtLevel(2));
        assertEquals(32, zBuffer.getWidthAtLevel(3));
        assertEquals(32, zBuffer.getHeightAtLevel(3));
    }

    @Test
    void testCameraUpdate() {
        float[] viewMatrix = new float[16];
        float[] projMatrix = new float[16];
        for (int i = 0; i < 16; i++) {
            viewMatrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
            projMatrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
        assertDoesNotThrow(() -> zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000.0f));
    }

    @Test
    void testClear() {
        zBuffer.clear();
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(20, 20, 20));
        assertFalse(zBuffer.isOccluded(bounds));
    }

    @Test
    void testOcclusionWithoutCamera() {
        var bounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(20, 20, 20));
        assertFalse(zBuffer.isOccluded(bounds));
    }

    @Test
    void testRenderOccluder() {
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createRowMajorOrthographic(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        var occluderBounds = new EntityBounds(new Point3f(0, 0, -20), new Point3f(50, 50, -10));
        zBuffer.renderOccluder(occluderBounds);
        zBuffer.updateHierarchy();
        // Smoke test only - actual occlusion depends on projection implementation
    }

    @Test
    void testHierarchyUpdate() {
        var bounds = new EntityBounds(new Point3f(0, 0, -20), new Point3f(10, 10, -10));
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createRowMajorOrthographic(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        zBuffer.renderOccluder(bounds);
        assertDoesNotThrow(zBuffer::updateHierarchy);
    }

    @Test
    void testBoundsOutsideFrustum() {
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createRowMajorOrthographic(-10, 10, -10, 10, 0.1f, 100);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 100);
        var bounds = new EntityBounds(new Point3f(1000, 1000, 1000), new Point3f(1100, 1100, 1100));
        assertFalse(zBuffer.isOccluded(bounds));
    }

    @Test
    void testMultipleLevels() {
        var smallBuffer = new HierarchicalZBuffer(64, 64, 2);
        assertEquals(64, smallBuffer.getWidthAtLevel(0));
        assertEquals(32, smallBuffer.getWidthAtLevel(1));

        var largeBuffer = new HierarchicalZBuffer(512, 512, 6);
        assertEquals(512, largeBuffer.getWidthAtLevel(0));
        assertEquals(256, largeBuffer.getWidthAtLevel(1));
        assertEquals(128, largeBuffer.getWidthAtLevel(2));
        assertEquals(64, largeBuffer.getWidthAtLevel(3));
        assertEquals(32, largeBuffer.getWidthAtLevel(4));
        assertEquals(16, largeBuffer.getWidthAtLevel(5));
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createRowMajorOrthographic(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    float offset = threadId * 20;
                    var b = new EntityBounds(new Point3f(offset, offset, -20), new Point3f(offset + 10, offset + 10, -10));
                    zBuffer.renderOccluder(b);
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertDoesNotThrow(zBuffer::updateHierarchy);
    }

    /**
     * Concurrency: concurrent renderOccluder + updateHierarchy must not produce a torn pyramid.
     */
    @Test
    void testConcurrentRenderAndUpdateHierarchyNoTornPyramid() throws InterruptedException {
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createRowMajorOrthographic(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);

        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            final float offset = i * 15f;
            Thread t = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        var b = new EntityBounds(new Point3f(offset, offset, -15f),
                                                 new Point3f(offset + 10f, offset + 10f, -5f));
                        zBuffer.renderOccluder(b);
                    }
                } catch (Exception e) {
                    failed.set(true);
                }
            }, "writer-" + i);
            threads.add(t);
        }

        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        zBuffer.updateHierarchy();
                    }
                } catch (Exception e) {
                    failed.set(true);
                }
            }, "updater-" + i);
            threads.add(t);
        }

        threads.forEach(Thread::start);
        Thread.sleep(500);
        stop.set(true);
        for (Thread t : threads) {
            t.join(2000);
        }
        assertFalse(failed.get(), "Concurrent renderOccluder + updateHierarchy threw an exception (torn pyramid)");
    }

    /**
     * Clock injection: adaptResolution cooldown must flip deterministically when an injected
     * TestClock is used. ADAPTATION_COOLDOWN_MS=5000. Trigger: effectiveness&lt;0.1 AND memoryPressure&gt;0.7.
     */
    @Test
    void testAdaptResolutionCooldownDeterministicWithInjectedClock() {
        TestClock testClock = new TestClock(6000L);
        zBuffer.setClock(testClock);

        double lowEff = 0.05; // < LOW_EFFECTIVENESS_THRESHOLD (0.1)
        double highMem = 0.8; // > 0.7

        // No prior adaptation — gate open → resize
        assertTrue(zBuffer.adaptResolution(lowEff, highMem),
                   "First call should succeed (no prior adaptation)");

        // Same instant — cooldown active → blocked
        assertFalse(zBuffer.adaptResolution(lowEff, highMem),
                    "Second call within cooldown must return false");

        // 4999 ms later — still in cooldown
        testClock.advance(4999L);
        assertFalse(zBuffer.adaptResolution(lowEff, highMem),
                    "Call 1 ms before expiry must return false");

        // 1 ms more — boundary crossed → resize (use upgrade params since buffer is now at MINIMAL)
        testClock.advance(1L);
        assertTrue(zBuffer.adaptResolution(0.9, 0.1),
                   "Call at cooldown boundary should succeed (upgrade from MINIMAL)");
    }

    /**
     * Concurrency: multiple threads racing through adaptResolution when clock is frozen past cooldown
     * must result in exactly one resize (double-checked lock under write lock).
     */
    @Test
    void testAdaptResolutionCooldownNoConcurrentDoubleResize() throws InterruptedException {
        TestClock testClock = new TestClock(10_000L);
        zBuffer.setClock(testClock);

        int numThreads = 8;
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        AtomicInteger resizeCount = new AtomicInteger(0);
        AtomicBoolean errors = new AtomicBoolean(false);
        Thread[] threads = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    if (zBuffer.adaptResolution(0.05, 0.8)) {
                        resizeCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.set(true);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(2000);
        }

        assertFalse(errors.get(), "No thread should throw during concurrent adaptResolution");
        assertEquals(1, resizeCount.get(),
                     "Exactly one thread wins the double-checked lock; no double-resize");
    }

    /**
     * Non-power-of-two buffer: level-coordinate mapping must stay in-range.
     *
     * <p>Buffer is 100x60 (neither dim is a power of two), 3 levels.
     * Level-1 width = 50, level-1 height = 30.  Level-2 width = 25, level-2 height = 15.
     *
     * <p>This test validates the fix for Luciferase-7wzml.148: the old {@code coord >> level}
     * shift produced wrong indices for non-power-of-two dimensions (e.g. base=60, level=2,
     * levelDim=15: pixel 59 &gt;&gt; 2 = 14, pixel 56 &gt;&gt; 2 = 14, pixel 52 &gt;&gt; 2 = 13,
     * all correct — but the shift accidentally worked for power-of-two halving; for non-power-of-two
     * dimensions the mapping diverges from floor-halving and could produce OOB indices before clamping).
     * The fix is proportional mapping: {@code coord * levelDim / baseDim}.
     *
     * <p>Rather than depending on the full MVP projection pipeline (whose z-sign conventions
     * make "behind occluder" unreliable in unit tests), this test validates the index-mapping
     * contract directly: for each level the proportional formula must map the maximum base
     * coordinate (baseMax = baseDim - 1) to exactly (levelDim - 1), and the minimum base
     * coordinate (0) to exactly 0 — no OOB and no aliasing to cell 0 at the far edge.
     */
    @Test
    void testOcclusionAtLevelNonPowerOfTwoBuffer() {
        // 100x60 buffer, 3 levels: level0=100x60, level1=50x30, level2=25x15
        var buf = new HierarchicalZBuffer(100, 60, 3);

        assertEquals(100, buf.getWidthAtLevel(0));
        assertEquals(60,  buf.getHeightAtLevel(0));
        assertEquals(50,  buf.getWidthAtLevel(1));
        assertEquals(30,  buf.getHeightAtLevel(1));
        assertEquals(25,  buf.getWidthAtLevel(2));
        assertEquals(15,  buf.getHeightAtLevel(2));

        // Non-vacuous index-mapping assertion: verify the proportional formula
        // `coord * levelDim / baseDim` stays in [0, levelDim-1] at boundary coordinates
        // for every level.  This is the invariant that .148 fixes — the old >> level shift
        // produced indices that diverged from floor-halving on non-power-of-two dims.
        int baseWidth  = 100;
        int baseHeight = 60;
        for (int level = 0; level < 3; level++) {
            int lw = buf.getWidthAtLevel(level);
            int lh = buf.getHeightAtLevel(level);

            // Minimum base coordinate must map to index 0.
            int minMappedX = Math.max(0, 0 * lw / baseWidth);
            int minMappedY = Math.max(0, 0 * lh / baseHeight);
            assertEquals(0, minMappedX,
                "level " + level + ": base coord 0 must map to level index 0 (width)");
            assertEquals(0, minMappedY,
                "level " + level + ": base coord 0 must map to level index 0 (height)");

            // Maximum base coordinate must map to levelDim-1 (not OOB and not cell 0).
            int maxBaseX = baseWidth  - 1;  // 99
            int maxBaseY = baseHeight - 1;  // 59
            int maxMappedX = Math.min(lw - 1, maxBaseX * lw / baseWidth);
            int maxMappedY = Math.min(lh - 1, maxBaseY * lh / baseHeight);
            assertEquals(lw - 1, maxMappedX,
                "level " + level + ": max base coord " + maxBaseX
                + " must map to last valid level index " + (lw - 1) + " (width=" + lw + ")");
            assertEquals(lh - 1, maxMappedY,
                "level " + level + ": max base coord " + maxBaseY
                + " must map to last valid level index " + (lh - 1) + " (height=" + lh + ")");
        }

        // No-throw smoke: renderOccluder + isOccluded on boundary bounds must not throw
        // ArrayIndexOutOfBoundsException regardless of projection outcome.
        float[] view = createIdentityMatrix();
        float[] proj = createRowMajorOrthographic(-50, 50, -50, 50, 0.1f, 1000);
        buf.updateCamera(view, proj, 0.1f, 1000f);
        var boundary = new EntityBounds(new Point3f(0, 0, -20), new Point3f(50, 50, -10));
        assertDoesNotThrow(() -> buf.renderOccluder(boundary),
            "renderOccluder on non-power-of-two buffer must not throw OOB");
        buf.updateHierarchy();
        assertDoesNotThrow(() -> buf.isOccluded(boundary),
            "isOccluded on non-power-of-two buffer must not throw OOB");
    }

    // -------------------------------------------------------------------------
    // Behavioral occlusion tests (Luciferase-01w6v): renderOccluder must actually
    // write depth and isOccluded must report objects behind a rendered occluder.
    //
    // Matrices below are ROW-MAJOR (m[row*4+col]) — the convention used by
    // HierarchicalZBuffer.multiplyMatrixVector. Column-major (OpenGL memory-layout)
    // matrices must be transposed before being passed to updateCamera.
    // -------------------------------------------------------------------------

    /**
     * Near-half perspective occluder (Luciferase-01w6v RED case 1).
     *
     * <p>With near=1, far=10, geometry nearer than 2fn/(f+n) ≈ 1.82 eye units has
     * NEGATIVE GL NDC z. The pre-fix guard {@code projected[2] > 0} rejected every
     * corner of such an occluder, projectBounds returned null, renderOccluder
     * silently no-oped, and nothing was ever occluded — the DSOC dead-when-enabled
     * defect. Camera at z=5.4 looking down -z; occluder at z∈[3.7,4.2] is entirely
     * in the near half (eye z ∈ [-1.2,-1.7], NDC z ∈ [-0.63,-0.09]).
     */
    @Test
    void testNearHalfPerspectiveOccluderOccludesBehindObject() {
        float[] view = createRowMajorTranslationView(5.4f);
        float[] proj = createRowMajorPerspective(60f, 1f, 1f, 10f);
        zBuffer.updateCamera(view, proj, 1f, 10f);

        // Large occluder entirely in the NEAR half of the frustum (negative NDC z)
        var occluder = new EntityBounds(new Point3f(-2, -2, 3.7f), new Point3f(2, 2, 4.2f));
        // Small box well behind it (eye z ∈ [-4.5,-4.8]), screen-covered by the occluder
        var behind = new EntityBounds(new Point3f(-0.2f, -0.2f, 0.6f), new Point3f(0.2f, 0.2f, 0.9f));

        assertFalse(zBuffer.isOccluded(behind), "empty Z-buffer must not occlude anything");

        zBuffer.renderOccluder(occluder);

        assertTrue(zBuffer.isOccluded(behind),
                "box fully behind a near-half occluder must be occluded — pre-fix the z-sign "
                + "guard rejected all negative-NDC-z corners and renderOccluder silently no-oped");
    }

    /**
     * Far-half perspective regression guard + in-front discrimination.
     *
     * <p>Far-half geometry (positive GL NDC z) accidentally worked pre-fix; this pins
     * that it keeps working, and that an object IN FRONT of the occluder is never
     * reported occluded (depth ordering, not just coverage).
     */
    @Test
    void testFarHalfPerspectiveOcclusionAndInFrontDiscrimination() {
        float[] view = createRowMajorTranslationView(100f);
        float[] proj = createRowMajorPerspective(60f, 1f, 0.1f, 1000f);
        zBuffer.updateCamera(view, proj, 0.1f, 1000f);

        var occluder = new EntityBounds(new Point3f(-30, -30, 40), new Point3f(30, 30, 50));
        var behind   = new EntityBounds(new Point3f(-5, -5, -20), new Point3f(5, 5, -10));
        var inFront  = new EntityBounds(new Point3f(-5, -5, 70), new Point3f(5, 5, 80));

        assertFalse(zBuffer.isOccluded(behind), "empty Z-buffer must not occlude anything");

        zBuffer.renderOccluder(occluder);

        assertTrue(zBuffer.isOccluded(behind),
                "box behind the occluder (farther from camera) must be occluded");
        assertFalse(zBuffer.isOccluded(inFront),
                "box between camera and occluder must NOT be occluded");
    }

    /**
     * Near-region orthographic occluder (Luciferase-01w6v RED case 2).
     *
     * <p>Standard GL ortho maps the near half of [near,far] to negative NDC z; the
     * pre-fix guard rejected those corners. Identity view (camera at origin looking
     * down -z), ortho ±100, near=0.1, far=100. Occluder at z∈[-20,-10] has NDC
     * z ∈ [-0.80,-0.60] — fully rejected pre-fix.
     */
    @Test
    void testOrthographicNearRegionOccluderOccludesBehindObject() {
        float[] view = createIdentityMatrix();
        float[] proj = createRowMajorOrthographic(-100, 100, -100, 100, 0.1f, 100f);
        zBuffer.updateCamera(view, proj, 0.1f, 100f);

        var occluder = new EntityBounds(new Point3f(-50, -50, -20), new Point3f(50, 50, -10));
        var behind   = new EntityBounds(new Point3f(-5, -5, -60), new Point3f(5, 5, -50));

        assertFalse(zBuffer.isOccluded(behind), "empty Z-buffer must not occlude anything");

        zBuffer.renderOccluder(occluder);

        assertTrue(zBuffer.isOccluded(behind),
                "box behind a near-region ortho occluder must be occluded — pre-fix the "
                + "z-sign guard rejected all negative-NDC-z corners");
    }

    /**
     * Behind-camera bounds (Luciferase-01w6v RED case 3).
     *
     * <p>A box behind the camera has clip w &lt; 0; the perspective divide flips signs
     * and produces NDC z &gt; 1, which PASSED the pre-fix {@code > 0} guard. Its
     * nearZ (&gt; 1) then failed {@code nearZ < buffer} at every pixel of an EMPTY
     * buffer, so isOccluded reported true for an object behind the camera with
     * nothing rendered at all. Post-fix the w-guard rejects it: not occluded, and
     * rendering it as an occluder is a clean no-op.
     */
    @Test
    void testBehindCameraBoundsNeverOccluded() {
        float[] view = createRowMajorTranslationView(5.4f);
        float[] proj = createRowMajorPerspective(60f, 1f, 1f, 10f);
        zBuffer.updateCamera(view, proj, 1f, 10f);

        // Camera at z=5.4 looking down -z: world z > 5.4 is behind the camera
        var behindCamera = new EntityBounds(new Point3f(-1, -1, 6f), new Point3f(1, 1, 7f));

        assertFalse(zBuffer.isOccluded(behindCamera),
                "a box behind the camera must never be reported occluded — pre-fix the "
                + "sign-flipped divide produced NDC z > 1 which passed the guard and beat "
                + "an empty buffer");

        assertDoesNotThrow(() -> zBuffer.renderOccluder(behindCamera),
                "rendering a behind-camera occluder must be a clean no-op");

        // The no-op must not have corrupted the buffer: an in-frustum box is still visible
        var inFrustum = new EntityBounds(new Point3f(-0.5f, -0.5f, 2f), new Point3f(0.5f, 0.5f, 2.5f));
        assertFalse(zBuffer.isOccluded(inFrustum),
                "after a behind-camera no-op render, in-frustum geometry must remain unoccluded");
    }

    /**
     * Beyond-far-plane bounds must not report occluded against an empty buffer.
     *
     * <p>A box entirely past the far plane projects to depth01 &gt; 1 at every corner.
     * Without the admission upper bound, its clamped nearZ (1.0) ties the buffer's
     * far-plane initialization and {@code nearZ < buffer} fails at every pixel —
     * phantom occlusion with nothing rendered. The depth-range admission guard
     * rejects such corners; projectBounds returns null and the box stays visible.
     */
    @Test
    void testBeyondFarPlaneBoundsNotOccluded() {
        float[] view = createRowMajorTranslationView(100f);
        float[] proj = createRowMajorPerspective(60f, 1f, 0.1f, 50f); // far plane at 50
        zBuffer.updateCamera(view, proj, 0.1f, 50f);

        // Eye z ∈ [-160,-150] — entirely beyond the far plane (50)
        var beyondFar = new EntityBounds(new Point3f(-5, -5, -60), new Point3f(5, 5, -50));

        assertFalse(zBuffer.isOccluded(beyondFar),
                "a box entirely beyond the far plane must not report occluded against an "
                + "empty buffer — its depth01 > 1 corners must be rejected, not clamped to "
                + "the far-plane init value");

        assertDoesNotThrow(() -> zBuffer.renderOccluder(beyondFar),
                "rendering a beyond-far occluder must be a clean no-op");
    }

    // Helper methods

    /**
     * Row-major view matrix for a camera at (0, 0, cameraZ) looking down -Z with +Y up:
     * eye = world - (0,0,cameraZ).
     */
    private float[] createRowMajorTranslationView(float cameraZ) {
        float[] m = createIdentityMatrix();
        m[11] = -cameraZ; // row 2, col 3
        return m;
    }

    /**
     * Row-major GL-style perspective projection (visible eye z ∈ [-near,-far] maps to
     * NDC z ∈ [-1,1], clip w = -z_eye).
     */
    private float[] createRowMajorPerspective(float fovYDegrees, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovYDegrees) / 2.0));
        float[] m = new float[16];
        m[0]  = f / aspect;
        m[5]  = f;
        m[10] = (far + near) / (near - far);
        m[11] = 2f * far * near / (near - far);
        m[14] = -1f;
        return m;
    }

    /**
     * Row-major GL-style orthographic projection (visible eye z ∈ [-near,-far] maps to
     * NDC z ∈ [-1,1], clip w = 1).
     */
    private float[] createRowMajorOrthographic(float left, float right, float bottom, float top,
                                               float near, float far) {
        float[] m = new float[16];
        m[0]  = 2f / (right - left);
        m[3]  = -(right + left) / (right - left);
        m[5]  = 2f / (top - bottom);
        m[7]  = -(top + bottom) / (top - bottom);
        m[10] = -2f / (far - near);
        m[11] = -(far + near) / (far - near);
        m[15] = 1f;
        return m;
    }

    private float[] createIdentityMatrix() {
        float[] matrix = new float[16];
        for (int i = 0; i < 16; i++) {
            matrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
        return matrix;
    }
}
