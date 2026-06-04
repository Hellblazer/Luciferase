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
package com.hellblazer.luciferase.esvt.gpu;

import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.system.Platform;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for ESVTGPUMemory lifecycle (AutoCloseable, idempotent close, no finalize).
 *
 * <p>Tests are split into two groups:
 * <ul>
 *   <li><b>Structural</b> — pure Java reflection/type checks, no LWJGL allocation, run on all platforms.</li>
 *   <li><b>Allocation</b> — require {@code memAlignedAlloc} (LWJGL native), skipped on macOS because
 *       the {@code gpu-macos} Maven profile adds {@code -XstartOnFirstThread} which constrains LWJGL
 *       native allocation to the OS main thread; surefire forks are not that thread.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class ESVTGPUMemoryTest {

    // -----------------------------------------------------------------------
    // Structural tests — no LWJGL allocation, run everywhere
    // -----------------------------------------------------------------------

    @Test
    void implementsAutoCloseable() {
        assertTrue(AutoCloseable.class.isAssignableFrom(ESVTGPUMemory.class),
                   "ESVTGPUMemory must implement AutoCloseable");
    }

    @Test
    void finalizeIsNotOverridden() {
        // ESVTGPUMemory must NOT declare a finalize() override — Cleaner is used instead.
        for (Method m : ESVTGPUMemory.class.getDeclaredMethods()) {
            assertNotEquals("finalize", m.getName(),
                            "ESVTGPUMemory must not override finalize() — use Cleaner instead");
        }
    }

    // -----------------------------------------------------------------------
    // Allocation tests — require LWJGL memAlignedAlloc on the correct thread.
    // Skipped on macOS where the gpu-macos surefire profile forces -XstartOnFirstThread
    // (surefire forked VMs are not thread 0 on macOS, causing a crash from LWJGL).
    // -----------------------------------------------------------------------

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void tryWithResourcesClosesAndMarksDisposed() {
        ESVTGPUMemory mem;
        try (var m = new ESVTGPUMemory(8, 0)) {
            mem = m;
            assertFalse(m.isDisposed(), "must not be disposed while open");
        }
        assertTrue(mem.isDisposed(), "must be disposed after try-with-resources exits");
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void doubleCloseIsNoOp() {
        var mem = new ESVTGPUMemory(8, 0);
        mem.close();
        assertTrue(mem.isDisposed());
        assertDoesNotThrow(mem::close, "double-close must be a no-op");
        assertTrue(mem.isDisposed());
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void doubleDisposeIsNoOp() {
        var mem = new ESVTGPUMemory(8, 0);
        mem.dispose();
        assertDoesNotThrow(mem::dispose, "double-dispose must be a no-op");
        assertTrue(mem.isDisposed());
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void closeDelegatesToDispose() {
        var mem = new ESVTGPUMemory(8, 0);
        mem.close();
        assertTrue(mem.isDisposed(), "close() must leave isDisposed()==true");
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void constructorInitializesCorrectly() {
        try (var mem = new ESVTGPUMemory(16, 3)) {
            assertEquals(16, mem.getNodeCount());
            assertEquals(3, mem.getRootType());
            assertTrue(mem.getBufferSize() > 0);
            assertFalse(mem.isDisposed());
        }
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void constructorRejectsInvalidArguments() {
        // These throw before any allocation — safe to run
        assertThrows(IllegalArgumentException.class, () -> new ESVTGPUMemory(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ESVTGPUMemory(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ESVTGPUMemory(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new ESVTGPUMemory(1, 6));
    }

    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void methodsThrowAfterClose() {
        var mem = new ESVTGPUMemory(8, 0);
        mem.close();
        assertThrows(IllegalStateException.class, () -> mem.getNodeBuffer());
        assertThrows(IllegalStateException.class,
                     () -> mem.writeNode(0, new ESVTNodeUnified((byte) 0)));
        assertThrows(IllegalStateException.class, () -> mem.readNode(0));
    }

    /**
     * C1 regression: dispose() → close() → dispose() must never double-free the native buffer.
     * The AtomicBoolean guard ensures memAlignedFree fires exactly once regardless of call order.
     */
    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void tripleDisposeCloseDisposeNoDoubleFree() {
        var mem = new ESVTGPUMemory(8, 0);
        // First free — normal path
        assertDoesNotThrow(mem::dispose, "first dispose() must succeed");
        assertTrue(mem.isDisposed());
        // close() delegates to dispose() — must be a no-op (freed guard already set)
        assertDoesNotThrow(mem::close, "close() after dispose() must be a no-op, not a double-free");
        assertTrue(mem.isDisposed());
        // Third call — must also be safe
        assertDoesNotThrow(mem::dispose, "second dispose() must be a no-op, not a double-free");
        assertTrue(mem.isDisposed());
    }

    /**
     * Verify that a freshly constructed instance can be close()-d safely without
     * ever calling dispose() explicitly — the AutoCloseable contract.
     */
    @Test
    @DisabledOnOs(value = OS.MAC, disabledReason = "gpu-macos surefire profile adds -XstartOnFirstThread; "
                                                   + "memAlignedAlloc must run on OS main thread — surefire forks are not")
    void freshInstanceCloseIsIdempotentNoOp() {
        var mem = new ESVTGPUMemory(4, 1);
        assertFalse(mem.isDisposed(), "freshly created instance must not be disposed");
        assertDoesNotThrow(mem::close, "first close() must succeed");
        assertTrue(mem.isDisposed());
        assertDoesNotThrow(mem::close, "second close() must be idempotent no-op");
        assertTrue(mem.isDisposed());
    }
}
