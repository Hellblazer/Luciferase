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
package com.hellblazer.luciferase.gpu;

import com.hellblazer.luciferase.esvo.gpu.OctreeGPUMemory;
import com.hellblazer.luciferase.esvt.gpu.ESVTGPUMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the node-SSBO size invariant for the fixed-size GPU-memory classes under Mesa software GL
 * (Luciferase-8pcrt, harness from Luciferase-ai9tw).
 *
 * <p><b>Why this is a same-size pin, not a resize-drift fix:</b> 8pcrt was filed suspecting the
 * z2ysz accounting drift (re-upload at a different size leaves the tracked {@code BufferResource}
 * size stale). Verification of the code disproves that for {@code OctreeGPUMemory} and
 * {@code ESVTGPUMemory}: both fix {@code bufferSize}/{@code nodeCount} as {@code final} at
 * construction, allocate {@code nodeBuffer} once, and {@code uploadToGPU()} re-uploads that same
 * fixed buffer — so every {@code glBufferData} is the same size and no drift is possible. The
 * {@code if (nodeSSBO == null)} create-once is therefore correct. (Unlike the data-driven
 * {@code ComputeShaderRenderer}/{@code ESVTComputeRenderer} far-pointer SSBOs, whose size varies
 * with the uploaded data — those genuinely needed the z2ysz recreate-on-resize fix.)</p>
 *
 * <p>These tests lock that invariant: the tracked size equals {@code nodeCount * NODE_SIZE_BYTES}
 * and stays constant across re-uploads. If a resize/grow path is ever added to either class, these
 * fail — forcing the author to apply the z2ysz recreate-the-tracked-resource pattern instead of
 * silently drifting the accounting. They also give the {@code uploadToGPU} GL path its first
 * execution coverage (under Mesa software GL; the dev hardware caps at GL 4.1).</p>
 *
 * @author hal.hildebrand
 */
@DisplayName("8pcrt: GPUMemory nodeSSBO size invariant under Mesa software GL")
class GPUMemoryAccountingGLTest extends GLComputeTestSupport {

    private static final long EIGHT_NODES_BYTES = 8L * 8L; // nodeCount(8) * NODE_SIZE_BYTES(8)

    @Test
    @DisplayName("8pcrt: OctreeGPUMemory nodeSSBO tracked size is fixed across re-uploads (no drift)")
    void octreeNodeSsboSizeIsStable() throws Exception {
        runWithGLContext(() -> {
            try (var mem = new OctreeGPUMemory(8)) {
                mem.uploadToGPU();
                long first = trackedNodeBytes(mem);
                assertEquals(EIGHT_NODES_BYTES, first,
                        "nodeSSBO tracked size must equal nodeCount * NODE_SIZE_BYTES");

                mem.uploadToGPU(); // re-upload of the same fixed buffer
                assertEquals(first, trackedNodeBytes(mem),
                        "8pcrt: re-upload must not drift the tracked size — OctreeGPUMemory is fixed-size per "
                        + "instance. If a resize path is ever added, recreate the tracked resource (see z2ysz).");
            }
        });
    }

    @Test
    @DisplayName("8pcrt: ESVTGPUMemory nodeSSBO tracked size is fixed across re-uploads (no drift)")
    void esvtNodeSsboSizeIsStable() throws Exception {
        runWithGLContext(() -> {
            try (var mem = new ESVTGPUMemory(8, 0)) {
                mem.uploadToGPU();
                long first = trackedNodeBytes(mem);
                assertEquals(EIGHT_NODES_BYTES, first,
                        "nodeSSBO tracked size must equal nodeCount * NODE_SIZE_BYTES");

                mem.uploadToGPU();
                assertEquals(first, trackedNodeBytes(mem),
                        "8pcrt: re-upload must not drift the tracked size — ESVTGPUMemory is fixed-size per "
                        + "instance. If a resize path is ever added, recreate the tracked resource (see z2ysz).");
            }
        });
    }

    /** Read the GPU-memory object's private {@code nodeSSBO} tracked size via reflection. */
    private static long trackedNodeBytes(Object mem) throws Exception {
        Field field = mem.getClass().getDeclaredField("nodeSSBO");
        field.setAccessible(true);
        var ssbo = field.get(mem);
        assertNotNull(ssbo, "nodeSSBO must be allocated after uploadToGPU");
        var getSizeBytes = ssbo.getClass().getMethod("getSizeBytes");
        return ((Number) getSizeBytes.invoke(ssbo)).longValue();
    }
}
