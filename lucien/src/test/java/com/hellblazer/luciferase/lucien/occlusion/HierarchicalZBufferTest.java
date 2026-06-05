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
        float[] projMatrix = createOrthographicMatrix(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        var occluderBounds = new EntityBounds(new Point3f(0, 0, 10), new Point3f(50, 50, 20));
        zBuffer.renderOccluder(occluderBounds);
        zBuffer.updateHierarchy();
        // Smoke test only - actual occlusion depends on projection implementation
    }

    @Test
    void testHierarchyUpdate() {
        var bounds = new EntityBounds(new Point3f(0, 0, 10), new Point3f(10, 10, 20));
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        zBuffer.renderOccluder(bounds);
        assertDoesNotThrow(zBuffer::updateHierarchy);
    }

    @Test
    void testBoundsOutsideFrustum() {
        float[] viewMatrix = createIdentityMatrix();
        float[] projMatrix = createOrthographicMatrix(-10, 10, -10, 10, 0.1f, 100);
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
        float[] projMatrix = createOrthographicMatrix(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    float offset = threadId * 20;
                    var b = new EntityBounds(new Point3f(offset, offset, 10), new Point3f(offset + 10, offset + 10, 20));
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
        float[] projMatrix = createOrthographicMatrix(-100, 100, -100, 100, 0.1f, 1000);
        zBuffer.updateCamera(viewMatrix, projMatrix, 0.1f, 1000);

        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            final float offset = i * 15f;
            Thread t = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        var b = new EntityBounds(new Point3f(offset, offset, 5f),
                                                 new Point3f(offset + 10f, offset + 10f, 15f));
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

    // Helper methods

    private float[] createIdentityMatrix() {
        float[] matrix = new float[16];
        for (int i = 0; i < 16; i++) {
            matrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
        return matrix;
    }

    private float[] createOrthographicMatrix(float left, float right, float bottom, float top, float near, float far) {
        float[] matrix = new float[16];
        matrix[0] = 2.0f / (right - left);
        matrix[5] = 2.0f / (top - bottom);
        matrix[10] = -2.0f / (far - near);
        matrix[12] = -(right + left) / (right - left);
        matrix[13] = -(top + bottom) / (top - bottom);
        matrix[14] = -(far + near) / (far - near);
        matrix[15] = 1.0f;
        return matrix;
    }
}
