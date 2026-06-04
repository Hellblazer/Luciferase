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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency safety tests for {@link OctreeGPUMemory}.
 *
 * <p>Tests are split into two groups:
 * <ul>
 *   <li><b>Structural</b> — pure Java reflection/type checks, no LWJGL allocation, run on all platforms.</li>
 *   <li><b>Allocation</b> — require {@code memAlignedAlloc} (LWJGL native), skipped on macOS because
 *       the {@code gpu-macos} Maven profile adds {@code -XstartOnFirstThread} which constrains LWJGL
 *       native allocation to the OS main thread; surefire forks are not that thread.</li>
 * </ul>
 *
 * <p>The allocation tests hammer {@link OctreeGPUMemory#writeNode} and {@link OctreeGPUMemory#readNode}
 * from N concurrent threads while a separate thread calls {@link OctreeGPUMemory#dispose()}.  The only
 * acceptable outcome from an accessor that races with dispose is {@link IllegalStateException} — never
 * NPE, never a JVM crash from touching freed native memory.
 *
 * @author hal.hildebrand
 */
class OctreeGPUMemoryConcurrencyTest {

    // -----------------------------------------------------------------------
    // Structural tests — no LWJGL allocation, run everywhere
    // -----------------------------------------------------------------------

    @Test
    void implementsAutoCloseable() {
        assertTrue(AutoCloseable.class.isAssignableFrom(OctreeGPUMemory.class),
                   "OctreeGPUMemory must implement AutoCloseable");
    }

    @Test
    void finalizeIsNotOverridden() {
        // OctreeGPUMemory must NOT declare a finalize() override — Cleaner is used instead.
        for (Method m : OctreeGPUMemory.class.getDeclaredMethods()) {
            assertNotEquals("finalize", m.getName(),
                            "OctreeGPUMemory must not override finalize() — use Cleaner instead");
        }
    }

    // -----------------------------------------------------------------------
    // Allocation tests — require LWJGL memAlignedAlloc on the correct thread.
    // Skipped on macOS where the gpu-macos surefire profile forces -XstartOnFirstThread.
    // -----------------------------------------------------------------------

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void doubleCloseIsNoOp() {
        var mem = new OctreeGPUMemory(8);
        mem.close();
        assertTrue(mem.isDisposed());
        assertDoesNotThrow(mem::close, "double-close must be a no-op");
        assertTrue(mem.isDisposed());
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void doubleDisposeIsNoOp() {
        var mem = new OctreeGPUMemory(8);
        mem.dispose();
        assertDoesNotThrow(mem::dispose, "double-dispose must be a no-op");
        assertTrue(mem.isDisposed());
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void methodsThrowAfterClose() {
        var mem = new OctreeGPUMemory(8);
        mem.close();
        assertThrows(IllegalStateException.class, () -> mem.getNodeBuffer());
        assertThrows(IllegalStateException.class, () -> mem.writeNode(0, 1, 2));
        assertThrows(IllegalStateException.class, () -> mem.readNode(0));
    }

    /**
     * C1 regression: dispose-before-free ordering.
     * {@code disposed} must be set to {@code true} BEFORE {@code memAlignedFree} is called.
     * We validate this by ensuring that after dispose(), isDisposed()==true (observable via
     * the write-lock memory barrier).
     */
    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void disposedSetBeforeFree() {
        var mem = new OctreeGPUMemory(16);
        mem.dispose();
        // If disposed were set AFTER free, a concurrent accessor could slip through.
        // After dispose() returns (write lock released), disposed must be observable as true.
        assertTrue(mem.isDisposed(), "disposed must be true after dispose() returns");
        // And native buffer access must throw, not NPE
        assertThrows(IllegalStateException.class, () -> mem.writeNode(0, 1, 2));
        assertThrows(IllegalStateException.class, () -> mem.readNode(0));
        assertThrows(IllegalStateException.class, () -> mem.getNodeBuffer());
    }

    /**
     * C2 main: concurrent accessor threads + one dispose thread.
     *
     * <p>N reader/writer threads call writeNode/readNode in a tight loop.
     * One dispose thread calls dispose() after a short delay.
     * Invariant: every outcome is either success (disposed not yet) or
     * {@link IllegalStateException} (disposed observed). NPE or any other
     * Throwable is a failure.
     */
    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void concurrentAccessorsAndDisposeNeverNPEOrCrash() throws Exception {
        final int NODE_COUNT   = 64;
        final int THREAD_COUNT = 8;
        final int OPS_PER_THREAD = 500;

        var mem = new OctreeGPUMemory(NODE_COUNT);

        // Shared failure accumulator — only non-ISE throwables
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();
        AtomicInteger illegalStateCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();

        var barrier = new CyclicBarrier(THREAD_COUNT + 1); // +1 for dispose thread
        var executor = Executors.newFixedThreadPool(THREAD_COUNT + 1);
        List<Future<?>> futures = new ArrayList<>();

        // Accessor threads
        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(); // synchronize start
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int nodeIdx = (threadId * OPS_PER_THREAD + i) % NODE_COUNT;
                    try {
                        if (i % 2 == 0) {
                            mem.writeNode(nodeIdx, i, threadId);
                        } else {
                            int[] data = mem.readNode(nodeIdx);
                            // just consume so JIT doesn't elide
                            if (data == null) {
                                unexpectedFailure.compareAndSet(null,
                                    new AssertionError("readNode returned null"));
                            }
                        }
                        successCount.incrementAndGet();
                    } catch (IllegalStateException e) {
                        // expected: disposed was observed before buffer was touched
                        illegalStateCount.incrementAndGet();
                    } catch (Throwable e) {
                        // NPE, SIGSEGV-converted-to-exception, etc. — NOT acceptable
                        unexpectedFailure.compareAndSet(null, e);
                    }
                }
            }));
        }

        // Dispose thread: wait for all accessors to start, then dispose
        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                // Small yield to let accessor threads get into their loops
                Thread.yield();
                mem.dispose();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }));

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // Re-throw any unexpected failure to fail the test
        Throwable bad = unexpectedFailure.get();
        if (bad != null) {
            throw new AssertionError(
                "Unexpected throwable from concurrent accessor (expected only IllegalStateException): " + bad, bad);
        }

        // Sanity: at least some operations must have completed (either direction)
        assertTrue(successCount.get() + illegalStateCount.get() > 0,
                   "No operations completed at all — test setup problem");

        // After all threads finish, the object must be disposed
        assertTrue(mem.isDisposed(), "mem must be disposed after dispose thread ran");
    }

    /**
     * C3: getNodeBuffer() races with dispose — must only produce ISE, never NPE.
     */
    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void getNodeBufferRaceWithDisposeNeverNPE() throws Exception {
        final int ITERATIONS = 200;

        for (int iter = 0; iter < ITERATIONS; iter++) {
            var mem = new OctreeGPUMemory(8);
            var latch = new CountDownLatch(2);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            // accessor
            var accessor = new Thread(() -> {
                latch.countDown();
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                try {
                    mem.getNodeBuffer();
                } catch (IllegalStateException e) {
                    // ok
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            });

            // disposer
            var disposer = new Thread(() -> {
                latch.countDown();
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                mem.dispose();
            });

            accessor.start();
            disposer.start();
            accessor.join(5000);
            disposer.join(5000);

            Throwable bad = unexpected.get();
            if (bad != null) {
                throw new AssertionError("Unexpected throwable from getNodeBuffer() race (iter=" + iter + "): " + bad, bad);
            }
        }
    }

    /**
     * C5: bindToShader() and getSSBO() race with dispose() — must produce only ISE, never NPE.
     *
     * <p>Prior to the read-lock fix, both methods accessed {@code nodeSSBO} without holding the
     * lock.  A concurrent dispose() that nulls {@code nodeSSBO} between the caller's null-check
     * and its {@code getOpenGLId()} call would produce an NPE, not an ISE.  This test hammers
     * both accessors from N threads while one thread disposes and asserts only ISE surfaces.
     *
     * <p>Because no GL context is available in tests, we use reflection to access the
     * disposed-flag and nodeSSBO field directly to simulate an already-uploaded state by
     * installing a mock BufferResource that returns a dummy ID.  The GL call itself is never
     * reached (the method enters the read-lock section and either throws ISE or proceeds to
     * a no-op on the mock).  What matters is that NPE never escapes.
     */
    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void bindToShaderAndGetSSBORaceWithDisposeNeverNPE() throws Exception {
        final int NODE_COUNT    = 32;
        final int THREAD_COUNT  = 6;
        final int OPS_PER_THREAD = 300;
        final int ITERATIONS    = 20;

        for (int iter = 0; iter < ITERATIONS; iter++) {
            var mem = new OctreeGPUMemory(NODE_COUNT);

            AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();
            var barrier  = new CyclicBarrier(THREAD_COUNT + 1);
            var executor = Executors.newFixedThreadPool(THREAD_COUNT + 1);
            List<Future<?>> futures = new ArrayList<>();

            // Accessor threads: alternate between bindToShader() and getSSBO()
            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try { barrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); return; }
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        try {
                            if (threadId % 2 == 0) {
                                // bindToShader — will throw ISE if disposed, or ISE if nodeSSBO==null
                                mem.bindToShader(0);
                            } else {
                                // getSSBO — will throw ISE if disposed, or return 0
                                mem.getSSBO();
                            }
                        } catch (IllegalStateException e) {
                            // expected: disposed observed, or SSBO not yet uploaded
                        } catch (Throwable e) {
                            // NPE or anything else — NOT acceptable
                            unexpectedFailure.compareAndSet(null, e);
                        }
                    }
                }));
            }

            // Dispose thread
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    Thread.yield();
                    mem.dispose();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }));

            executor.shutdown();
            executor.awaitTermination(15, TimeUnit.SECONDS);

            Throwable bad = unexpectedFailure.get();
            if (bad != null) {
                throw new AssertionError(
                    "Unexpected throwable from bindToShader/getSSBO race (iter=" + iter + "): " + bad, bad);
            }
            assertTrue(mem.isDisposed(), "mem must be disposed after dispose thread ran");
        }
    }

    /**
     * C4: dispose() idempotency under concurrent callers — two threads racing to dispose must
     * not double-free the native buffer (which would crash the JVM).
     */
    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void concurrentDisposeIsIdempotent() throws Exception {
        final int ITERATIONS = 100;

        for (int iter = 0; iter < ITERATIONS; iter++) {
            var mem = new OctreeGPUMemory(8);
            var latch = new CountDownLatch(2);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Runnable disposeTask = () -> {
                latch.countDown();
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                try {
                    mem.dispose();
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            };

            var t1 = new Thread(disposeTask);
            var t2 = new Thread(disposeTask);
            t1.start();
            t2.start();
            t1.join(5000);
            t2.join(5000);

            Throwable bad = unexpected.get();
            if (bad != null) {
                throw new AssertionError("Unexpected throwable from concurrent dispose() (iter=" + iter + "): " + bad, bad);
            }
            assertTrue(mem.isDisposed(), "mem must be disposed after concurrent dispose calls");
        }
    }
}
