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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVOOpenCLRenderer far-pointer guard (Luciferase-7wzml.160).
 *
 * <p>Far-pointer kernel wiring is NOT yet implemented in the ESVO OpenCL path
 * (the kernel signature has no farPointers[] buffer argument and the traversal
 * code never checks FAR_FLAG_BIT). Uploading a far-pointer octree would produce
 * silently wrong geometry. The guard ensures fail-loud behaviour instead.
 *
 * <p>All tests are CPU-side (no GPU/OpenCL required): they exercise the
 * octreeToByteBuffer conversion path via reflection and the renderer constructor
 * path to keep CI green without any GPU device.
 */
class ESVOOpenCLRendererFarPointerTest {

    /**
     * A near-only octree (no far pointers) must NOT throw. Verifies that the guard
     * is guarding the right condition and does not over-fire.
     */
    @Test
    void nearOnlyOctree_doesNotThrow() throws Exception {
        var data = buildNearOnlyOctree();
        var renderer = new ESVOOpenCLRenderer(64, 64);

        // Invoke octreeToByteBuffer via reflection (private method, CPU-side only)
        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        // Must not throw — near pointers are fully supported
        assertDoesNotThrow(() -> {
            var buf = method.invoke(renderer, data);
            assertNotNull(buf, "ByteBuffer must be non-null for near-only octree");
        }, "Near-only octree must not trigger the far-pointer guard");
    }

    /**
     * An octree with one or more far pointers must throw UnsupportedOperationException
     * rather than silently producing wrong node indices in the kernel.
     */
    @Test
    void farPointerOctree_failsLoud() throws Exception {
        var data = buildFarPointerOctree();
        var renderer = new ESVOOpenCLRenderer(64, 64);

        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(renderer, data),
                "Far-pointer octree must throw, not silently render wrong geometry");

        assertInstanceOf(UnsupportedOperationException.class, ex.getCause(),
                "Root cause must be UnsupportedOperationException (not silent corrupt)");

        // Message must be actionable — reference the tracking bead
        var msg = ex.getCause().getMessage();
        assertNotNull(msg, "Exception message must not be null");
        assertTrue(msg.toLowerCase().contains("far pointer") || msg.toLowerCase().contains("far-pointer"),
                "Exception message must mention 'far pointer': " + msg);
        assertTrue(msg.contains("Luciferase-7wzml.160") || msg.contains("8putk"),
                "Exception message must reference a tracking bead: " + msg);
    }

    /**
     * Empty far-pointer array (length 0) must NOT trigger the guard — an empty
     * array is equivalent to no far pointers.
     */
    @Test
    void emptyFarPointerArray_doesNotThrow() throws Exception {
        var data = buildNearOnlyOctree();
        // Explicitly set empty array (same as default)
        data.setFarPointers(new int[0]);

        var renderer = new ESVOOpenCLRenderer(64, 64);
        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(renderer, data),
                "Empty far-pointer array must not trigger the guard");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ESVOOctreeData buildNearOnlyOctree() {
        var data = new ESVOOctreeData(1024);
        // Root node — no far flag, childPtr = 1 (fits in 14 bits)
        var root = new ESVONodeUnified((byte) 0x01, (byte) 0x01, false, 1, (byte) 0, 0);
        data.setNode(0, root);
        // Child node — leaf
        var child = new ESVONodeUnified((byte) 0x00, (byte) 0x00, false, 0, (byte) 0, 1);
        data.setNode(1, child);
        // No far pointers set
        return data;
    }

    private ESVOOctreeData buildFarPointerOctree() {
        var data = new ESVOOctreeData(1024);
        // Root node with far flag set (isFar=true, childPtr is an index into farPointers[])
        var root = new ESVONodeUnified((byte) 0x01, (byte) 0x01, true, 0, (byte) 0, 0);
        data.setNode(0, root);
        var child = new ESVONodeUnified((byte) 0x00, (byte) 0x00, false, 0, (byte) 0, 1);
        data.setNode(1, child);
        // Wire a real farPointers table so the data is consistent
        data.setFarPointers(new int[] { 1 });
        return data;
    }
}
