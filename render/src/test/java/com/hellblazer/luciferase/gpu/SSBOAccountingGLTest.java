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

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.gpu.ComputeShaderRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GL SSBO size-accounting regression tests, run under the Mesa software GL context (Luciferase-ai9tw).
 *
 * <p>These exercise the actual {@code glBufferData} resize path that this project's dev hardware
 * (Apple Silicon, OpenGL 4.1) cannot run — the very paths that {@code z2ysz} had to ship review-only.
 * Now that CI's {@code mesa-software-test} job provides a software GL 4.5 context, the fix is verified
 * by execution: after a re-upload with a different far-pointer table size, the
 * {@link org.lwjgl.system.MemoryUtil}-tracked {@code BufferResource} size must follow the resize.</p>
 *
 * @author hal.hildebrand
 */
@DisplayName("ai9tw: GL SSBO size-accounting under Mesa software GL")
class SSBOAccountingGLTest extends GLComputeTestSupport {

    /**
     * z2ysz: ComputeShaderRenderer.uploadData recreates the far-pointer SSBO on size change so the
     * tracked BufferResource.sizeBytes follows glBufferData's resize (grow AND shrink), rather than
     * staying pinned at the first upload's byte count.
     */
    @Test
    @DisplayName("z2ysz: far-pointer SSBO tracked size follows re-upload resize (grow + shrink)")
    void farPointerSsboTrackedSizeFollowsResize() throws Exception {
        runWithGLContext(() -> {
            var renderer = new ComputeShaderRenderer(64, 64);
            try {
                renderer.initialize();

                var small = new ESVOOctreeData(4096);
                small.setFarPointers(new int[4]);            // 16 bytes
                renderer.uploadData(small);
                assertEquals(16L, trackedFarPointerBytes(renderer),
                        "tracked size must match the first upload's far-pointer byte count");

                var large = new ESVOOctreeData(4096);
                large.setFarPointers(new int[64]);           // 256 bytes
                renderer.uploadData(large);
                assertEquals(256L, trackedFarPointerBytes(renderer),
                        "z2ysz: tracked size must follow the re-upload GROW, not stay at the original 16 bytes");

                var back = new ESVOOctreeData(4096);
                back.setFarPointers(new int[1]);             // 4 bytes
                renderer.uploadData(back);
                assertEquals(4L, trackedFarPointerBytes(renderer),
                        "z2ysz: tracked size must follow the re-upload SHRINK too");
            } finally {
                renderer.dispose();
            }
        });
    }

    /** Read the renderer's private far-pointer SSBO tracked size via reflection. */
    private static long trackedFarPointerBytes(ComputeShaderRenderer renderer) throws Exception {
        Field field = ComputeShaderRenderer.class.getDeclaredField("farPointerSSBO");
        field.setAccessible(true);
        var ssbo = field.get(renderer);
        assertNotNull(ssbo, "farPointerSSBO must be allocated after uploadData");
        var getSizeBytes = ssbo.getClass().getMethod("getSizeBytes");
        return ((Number) getSizeBytes.invoke(ssbo)).longValue();
    }
}
