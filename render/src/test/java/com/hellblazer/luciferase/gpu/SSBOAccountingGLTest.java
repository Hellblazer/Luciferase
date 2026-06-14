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
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.esvt.gpu.ESVTComputeRenderer;
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

    /**
     * z2ysz: ESVTComputeRenderer (the sibling of ComputeShaderRenderer) carries the identical
     * far-pointer SSBO recreate-on-size-change fix; verify it under software GL too so a regression
     * in either parallel implementation fails the gate.
     */
    @Test
    @DisplayName("z2ysz: far-pointer SSBO tracked size follows re-upload resize (ESVTComputeRenderer)")
    void esvtFarPointerSsboTrackedSizeFollowsResize() throws Exception {
        runWithGLContext(() -> {
            var renderer = new ESVTComputeRenderer(64, 64);
            try {
                renderer.initialize();

                renderer.uploadData(esvtWithFarPointers(4));     // 16 bytes
                assertEquals(16L, trackedFarPointerBytes(renderer),
                        "tracked size must match the first upload's far-pointer byte count");

                renderer.uploadData(esvtWithFarPointers(64));    // 256 bytes
                assertEquals(256L, trackedFarPointerBytes(renderer),
                        "z2ysz: tracked size must follow the re-upload GROW (ESVTComputeRenderer)");

                renderer.uploadData(esvtWithFarPointers(1));     // 4 bytes
                assertEquals(4L, trackedFarPointerBytes(renderer),
                        "z2ysz: tracked size must follow the re-upload SHRINK (ESVTComputeRenderer)");
            } finally {
                renderer.dispose();
            }
        });
    }

    /**
     * z9qq4: ESVTComputeRenderer now wires the ContourBuffer SSBO (binding 6). Its size is
     * data-driven (contourCount * 4), so it uses the z2ysz recreate-on-size-change guard; with no
     * contours a 4-byte placeholder keeps the readonly binding slot valid. Verify upload, grow,
     * shrink, and the placeholder under software GL.
     */
    @Test
    @DisplayName("z9qq4: contour SSBO (binding 6) tracks data-driven size + placeholder (ESVTComputeRenderer)")
    void esvtContourSsboTracksData() throws Exception {
        runWithGLContext(() -> {
            var renderer = new ESVTComputeRenderer(64, 64);
            try {
                renderer.initialize();

                renderer.uploadData(esvtWithContours(8));     // 32 bytes
                assertEquals(32L, trackedSsboBytes(renderer, "contourSSBO"),
                        "contour SSBO tracked size must match contourCount * 4 on first upload");

                renderer.uploadData(esvtWithContours(64));    // 256 bytes
                assertEquals(256L, trackedSsboBytes(renderer, "contourSSBO"),
                        "z9qq4/z2ysz: contour SSBO tracked size must follow the re-upload GROW");

                renderer.uploadData(esvtWithContours(2));     // 8 bytes
                assertEquals(8L, trackedSsboBytes(renderer, "contourSSBO"),
                        "z9qq4/z2ysz: contour SSBO tracked size must follow the re-upload SHRINK");

                renderer.uploadData(esvtWithFarPointers(1));  // no contours -> 4-byte placeholder
                assertEquals(4L, trackedSsboBytes(renderer, "contourSSBO"),
                        "z9qq4: a build with no contours must bind a 4-byte placeholder, not stay at 8 bytes");
            } finally {
                renderer.dispose();
            }
        });
    }

    /** Minimal ESVTData carrying a far-pointer table of {@code count} entries. */
    private static ESVTData esvtWithFarPointers(int count) {
        var nodes = new ESVTNodeUnified[] { new ESVTNodeUnified() };
        return new ESVTData(nodes, new int[0], new int[count], 0, 3, 1, 0);
    }

    /** Minimal ESVTData carrying a contour table of {@code count} entries (no far pointers). */
    private static ESVTData esvtWithContours(int count) {
        var nodes = new ESVTNodeUnified[] { new ESVTNodeUnified() };
        return new ESVTData(nodes, new int[count], new int[0], 0, 3, 1, 0);
    }

    /** Read the renderer's private {@code farPointerSSBO} tracked size via reflection. */
    private static long trackedFarPointerBytes(Object renderer) throws Exception {
        return trackedSsboBytes(renderer, "farPointerSSBO");
    }

    /** Read a renderer's private {@link org.lwjgl}-backed SSBO field's tracked size via reflection. */
    private static long trackedSsboBytes(Object renderer, String fieldName) throws Exception {
        Field field = renderer.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        var ssbo = field.get(renderer);
        assertNotNull(ssbo, fieldName + " must be allocated after uploadData");
        var getSizeBytes = ssbo.getClass().getMethod("getSizeBytes");
        return ((Number) getSizeBytes.invoke(ssbo)).longValue();
    }
}
