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

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTOpenCLRenderer far-pointer wiring (Luciferase-8putk).
 *
 * <p>Far pointers must be wired end-to-end:
 * <ul>
 *   <li>{@code esvt_ray_traversal.cl} must receive a {@code farPointers[]} buffer (arg 2)
 *       and resolve far-flagged childPtr via indirection table, NOT in-band node read.</li>
 *   <li>{@link ESVTOpenCLRenderer#uploadDataBuffers} must upload the far-pointer table as
 *       a separate {@code cl_mem} buffer alongside the node and contour buffers.</li>
 *   <li>When no far pointers are present a minimal 4-byte placeholder buffer is still
 *       uploaded so the kernel arg slot is always bound.</li>
 * </ul>
 *
 * <p>All headless tests are CPU-side (no GPU/OpenCL required): they exercise the
 * byte-buffer layout and CPU resolution path.  The GPU render test is gated behind
 * {@code RUN_GPU_TESTS=true}.
 */
class ESVTOpenCLRendererFarPointerTest {

    // -------------------------------------------------------------------------
    // Headless CPU-side correctness tests
    // -------------------------------------------------------------------------

    /**
     * A near-only tree (no far pointers) must NOT throw.
     */
    @Test
    void nearOnlyTree_doesNotThrow() {
        var data = buildNearOnlyTree();
        assertDoesNotThrow(() -> {
            // ESVTData.nodesToByteBuffer() exercises the serialisation path used by the renderer
            var buf = data.nodesToByteBuffer();
            assertNotNull(buf, "ByteBuffer must be non-null for near-only tree");
        }, "Near-only tree must not throw");
    }

    /**
     * A tree with far pointers must also succeed.
     */
    @Test
    void farPointerTree_doesNotThrow() {
        var data = buildFarPointerTree();
        assertDoesNotThrow(() -> {
            var buf = data.nodesToByteBuffer();
            assertNotNull(buf, "ByteBuffer must be non-null for far-pointer tree");
        }, "Far-pointer tree must not throw");
    }

    /**
     * Empty far-pointer array (length 0) must NOT throw.
     */
    @Test
    void emptyFarPointerArray_doesNotThrow() {
        var data = buildNearOnlyTree();
        // Rebuild with explicit empty far pointer array
        var dataWithEmpty = ESVTData.builder(data).farPointers(new int[0]).build();
        assertDoesNotThrow(() -> dataWithEmpty.nodesToByteBuffer(),
                "Empty far-pointer array must not throw");
    }

    /**
     * Verify byte-buffer layout for a near-only tree:
     * 2 nodes × 8 bytes each = 16 bytes; root childPtr at bits 17-31 (15-bit field,
     * mask 0x7FFF), far flag at bit 16 = 0x10000.
     */
    @Test
    void nearOnlyTree_byteBufferLayout() {
        var data = buildNearOnlyTree();
        var buf = data.nodesToByteBuffer();
        assertNotNull(buf);

        // 2 nodes × 8 bytes (childDescriptor + contourDescriptor)
        assertEquals(2 * ESVTNodeUnified.SIZE_BYTES, buf.limit(),
                "Near-only tree with 2 nodes should produce " + (2 * ESVTNodeUnified.SIZE_BYTES) + " bytes");

        // Root node (index 0): childPtr stored in bits 17-31 (15 bits), mask 0x7FFF
        int rootDescriptor = buf.getInt(0);
        assertEquals(0, rootDescriptor & 0x10000,
                "Root node must not have far flag (bit 16 = 0x10000) set");
        assertEquals(1, (rootDescriptor >> 17) & 0x7FFF,
                "Root childPtr (bits 17-31, mask 0x7FFF) must be 1");
    }

    /**
     * Verify the far flag (bit 16 = 0x10000) is set in the byte buffer for a far-pointer tree.
     */
    @Test
    void farPointerTree_byteBufferHasFarFlagSet() {
        var data = buildFarPointerTree();
        var buf = data.nodesToByteBuffer();
        assertNotNull(buf);

        // Root node (index 0): far flag (bit 16 = 0x10000) must be set
        int rootDescriptor = buf.getInt(0);
        assertNotEquals(0, rootDescriptor & 0x10000,
                "Root descriptor must have far flag (bit 16 = 0x10000) set for far-pointer tree");
    }

    /**
     * CRITICAL: far node at non-zero index — catches the relative==absolute coincidence
     * that root-only tests hide.
     *
     * <p>Layout:
     * <ul>
     *   <li>index 0 — root (near, childPtr = relative 2, children start at index 2)</li>
     *   <li>index 1 — filler leaf</li>
     *   <li>index 2 — far interior node; children start at index 5 → relative = 5 - 2 = 3;
     *       childPtr = 0 (index into farPointers[]); farPointers[0] = 3</li>
     *   <li>index 3, 4 — filler leaves</li>
     *   <li>index 5 — first child of the far node</li>
     * </ul>
     *
     * <p>Verifies:
     * <ol>
     *   <li>farPointers[0] stores the RELATIVE offset (3, not the absolute index 5).</li>
     *   <li>{@link ESVTNodeUnified#getChildIndex(int, int, int[])} resolves to 5:
     *       currentNodeIdx(2) + relativeOffset(3) + popcount(0) = 5.</li>
     * </ol>
     */
    @Test
    void farNodeAtNonZeroIndex_cpuResolutionCorrect() {
        // leafMask convention: bits live in the PARENT. node[2].leafMask=0x01 marks
        // Morton slot 0 (node[5]) as a leaf. Filler nodes carry no leafMask.
        var nodes = new ESVTNodeUnified[6];

        // Root: near, childMask=0x01, childPtr=2 (child node[2] is interior)
        nodes[0] = new ESVTNodeUnified();
        nodes[0].setChildMask(0x01);
        nodes[0].setChildPtr(2);
        // leafMask=0x00 (default): child node[2] is interior

        // Index 1: unreachable filler
        nodes[1] = new ESVTNodeUnified();

        // Index 2: far interior node — childMask=0x01 (one child), far=true, childPtr=0 (farPointers[0])
        // farPointers[0] = 3 (relative: firstChildIndex=5, currentIndex=2, diff=3)
        // leafMask=0x01: parent marks Morton slot 0 (node[5]) as a leaf
        nodes[2] = new ESVTNodeUnified();
        nodes[2].setChildMask(0x01);
        nodes[2].setLeafMask(0x01); // bit 0: Morton child 0 (node[5]) is a leaf
        nodes[2].setFar(true);
        nodes[2].setChildPtr(0);

        // Indices 3, 4: unreachable fillers
        nodes[3] = new ESVTNodeUnified();
        nodes[4] = new ESVTNodeUnified();

        // Index 5: leaf content node (parent node[2] marks it via leafMask bit 0)
        nodes[5] = new ESVTNodeUnified();

        int relativeOffset = 5 - 2; // = 3
        var data = ESVTData.builder()
                           .nodes(nodes)
                           .farPointers(new int[]{ relativeOffset })
                           .rootType(0)
                           .maxDepth(3)
                           .leafCount(4)
                           .internalCount(2)
                           .build();

        // Verify far-pointer encoding: must be RELATIVE, not absolute
        int[] fp = data.farPointers();
        assertNotNull(fp, "farPointers must be set");
        assertEquals(1, fp.length, "Exactly one far pointer expected");
        assertEquals(3, fp[0],
                "farPointers[0] must store the RELATIVE offset (firstChildIndex - currentIndex = 5 - 2 = 3)");

        // Verify CPU getChildIndex for the far node at index 2
        // childMask=0x01, childIdx=0, popcount = 0
        // Expected: currentNodeIdx(2) + relativeOffset(3) + 0 = 5
        ESVTNodeUnified farNode = data.nodes()[2];
        assertTrue(farNode.isFar(), "Node at index 2 must have isFar()=true");
        int resolvedIndex = farNode.getChildIndex(0, 2, fp);
        assertEquals(5, resolvedIndex,
                "CPU getChildIndex for far node at index 2, childIdx=0 must resolve to 5 "
                + "(currentNodeIdx=2 + relativeOffset=3 + popcount=0)");
    }

    /**
     * Kernel source MUST contain the exact {@code getChildIndex} signature with
     * {@code __global const int* farPointers} as its first parameter. Also asserts:
     * <ul>
     *   <li>Mask {@code 0x7FFF} (15-bit childPtr) is present — NOT {@code 0x3FFF} (14-bit).</li>
     *   <li>In-band node read for far case ({@code nodes[parentIdx + childPtr].childDescriptor})
     *       is ABSENT — the defect this fix removes.</li>
     * </ul>
     *
     * <p>The signature substring check is exact so comments cannot produce false positives.
     */
    @Test
    void kernelSource_containsFarPointersArg() {
        var source = ESVTKernels.getOpenCLKernel();
        assertNotNull(source, "Kernel source must not be null");

        // farPointers buffer must appear in the traverseESVT signature
        assertTrue(source.contains("__global const int* farPointers"),
                "Kernel traverseESVT must declare '__global const int* farPointers' parameter");

        // getChildIndex must accept farPointers: check the exact declaration to avoid comment hits
        assertTrue(source.contains("getChildIndex(__global const int* farPointers"),
                "getChildIndex must declare exact signature 'getChildIndex(__global const int* farPointers ...'");

        // 15-bit mask 0x7FFF must be present (replaces old 14-bit 0x3FFF)
        assertTrue(source.contains("0x7FFF"),
                "Kernel must use 15-bit childPtr mask 0x7FFF (not 14-bit 0x3FFF)");

        // The old in-band defect must be ABSENT:
        //   nodes[parentIdx + childPtr].childDescriptor  — the wrong far resolution
        assertFalse(source.contains("nodes[parentIdx + childPtr].childDescriptor"),
                "Kernel must NOT use in-band nodes[] read for far resolution "
                + "(old defect: nodes[parentIdx + childPtr].childDescriptor)");
    }

    /**
     * GLSL compute shader source must carry the same far-pointer fixes as the OpenCL path:
     * <ul>
     *   <li>Contains {@code farPointers} — the binding-7 SSBO identifier.</li>
     *   <li>Contains {@code 0x7FFFu} — 15-bit childPtr mask (replaces old 14-bit {@code 0x3FFFu}).</li>
     *   <li>Does NOT contain {@code 0x3FFFu} — old, wrong mask.</li>
     *   <li>Does NOT contain {@code esvt.nodes[parentIdx + childPtr].x} — the in-band far
     *       resolution defect that this PR removes.</li>
     * </ul>
     *
     * <p>Headless: no GL context required — reads shader source string directly from
     * {@link ESVTKernels#GLSL_RAY_TRAVERSAL}.
     */
    @Test
    void glslShaderSource_containsFarPointersBinding() {
        var source = ESVTKernels.GLSL_RAY_TRAVERSAL;
        assertNotNull(source, "GLSL shader source must not be null");
        assertFalse(source.isEmpty(), "GLSL shader source must not be empty");

        // Binding 7 SSBO identifier must be present
        assertTrue(source.contains("farPointers"),
                "GLSL shader must declare farPointers SSBO (binding 7)");

        // 15-bit mask must be present
        assertTrue(source.contains("0x7FFFu"),
                "GLSL shader must use 15-bit childPtr mask 0x7FFFu (not 0x3FFFu)");

        // Old 14-bit mask must be gone
        assertFalse(source.contains("0x3FFFu"),
                "GLSL shader must NOT contain the old 14-bit childPtr mask 0x3FFFu");

        // In-band far resolution via nodes[] must be gone
        assertFalse(source.contains("esvt.nodes[parentIdx + childPtr].x"),
                "GLSL shader must NOT perform in-band nodes[] read for far resolution "
                + "(old defect: esvt.nodes[parentIdx + childPtr].x)");
    }

    // -------------------------------------------------------------------------
    // GPU render test — gated behind RUN_GPU_TESTS=true
    // -------------------------------------------------------------------------

    /**
     * GPU renders a far-pointer tree and actually HITS the leaf reached through the far pointer.
     *
     * <p>Asserts: render completes without exception; output buffer is non-null with the
     * expected byte count; and at least one ray reports a real leaf hit via the normal
     * buffer's hit flag (w > 0.5). The hit-count assertion distinguishes a genuine
     * far-pointer-resolved leaf hit from the pre-fix silhouette defect where the kernel
     * flagged every root-cube-intersecting ray as a hit regardless of tree content
     * (Luciferase-6fui1 defect A) — a render-without-throwing check cannot tell those apart.
     *
     * <p>Run with: {@code RUN_GPU_TESTS=true mvn test -pl render
     * -Dtest=ESVTOpenCLRendererFarPointerTest#farPointerTree_gpuRendersWithoutThrowing}
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true",
            disabledReason = "GPU test requires a real OpenCL device — set RUN_GPU_TESTS=true")
    void farPointerTree_gpuRendersWithoutThrowing() {
        if (!ESVTOpenCLRenderer.isOpenCLAvailable()) {
            System.out.println("OpenCL not available on this machine — skipping GPU far-pointer render test");
            return;
        }

        // Use a non-degenerate 6-node tree (far node at index 2, children at 5, farPointers[0]=3)
        // to avoid the root-only coincidence where relative == absolute.
        var data = buildNonDegenerateFarPointerTree();

        try (var renderer = new ESVTOpenCLRenderer(64, 64)) {
            renderer.initialize();
            renderer.uploadData(data);

            var cameraPos = new javax.vecmath.Vector3f(0.5f, 0.5f, 2.0f);
            var lookAt = new javax.vecmath.Vector3f(0.5f, 0.5f, 0.5f);
            assertDoesNotThrow(() -> renderer.renderFrame(cameraPos, lookAt, 60.0f),
                    "GPU traversal of far-pointer tree must not throw");

            var outputImage = renderer.getOutputImage();
            assertNotNull(outputImage, "Output image must be non-null after rendering");
            assertEquals(64 * 64 * 4, outputImage.limit(),
                    "Output image must be 64×64 RGBA = 16384 bytes");

            // Real-hit assertion: the leaf at node 5 (root -> node 2 via near ptr -> node 5 via
            // farPointers[0]) occupies the Bey-0-of-Bey-0 region near the origin corner; from
            // this camera some rays must reach it. The hit flag is hitNormals.w > 0.5 — only
            // written on a genuine leaf hit (root-without-leaf is a MISS per the kernel pin).
            var normalBuffer = renderer.getNormalBufferForTesting();
            int hitCount = 0;
            for (int i = 0; i < 64 * 64; i++) {
                if (normalBuffer.get(i * 4 + 3) > 0.5f) {
                    hitCount++;
                }
            }
            assertTrue(hitCount > 0,
                    "At least one ray must report a real leaf hit through the far pointer "
                    + "(hitNormals.w > 0.5); zero hits means far resolution or leaf detection broke");

            System.out.println("GPU far-pointer render test: completed, " + hitCount + " leaf-hit rays ("
                               + data.nodeCount() + " nodes, "
                               + data.farPointerCount() + " far pointer(s)).");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Minimal 2-node near-only tree: root (childPtr=1, near) + leaf child.
     * leafMask convention: bit lives in the PARENT. Root's leafMask=0x01 marks
     * Morton slot 0 (node[1]) as a leaf. node[1] itself carries no leafMask.
     */
    private ESVTData buildNearOnlyTree() {
        var nodes = new ESVTNodeUnified[2];

        // Root: near, childMask=0x01 (Morton slot 0 exists), childPtr=1, leafMask=0x01 (slot 0 is leaf)
        nodes[0] = new ESVTNodeUnified();
        nodes[0].setChildMask(0x01);
        nodes[0].setChildPtr(1);
        nodes[0].setLeafMask(0x01); // parent marks Morton child 0 (node[1]) as a leaf

        // Child: leaf content node (parent marks it — no leafMask here)
        nodes[1] = new ESVTNodeUnified();

        return ESVTData.builder()
                       .nodes(nodes)
                       .rootType(0)
                       .maxDepth(2)
                       .leafCount(1)
                       .internalCount(1)
                       .build();
    }

    /**
     * 2-node far-pointer tree: root (far=true, childPtr=0) + leaf child at index 1.
     * farPointers[0] = 1 (relative: firstChildIndex=1, currentIndex=0, diff=1).
     * For root at index 0, relative == absolute — so this is the simple root-only case.
     * leafMask convention: root's leafMask=0x01 marks Morton slot 0 (node[1]) as a leaf.
     */
    private ESVTData buildFarPointerTree() {
        var nodes = new ESVTNodeUnified[2];

        // Root: far=true, childMask=0x01, childPtr=0 (index into farPointers[]), leafMask=0x01
        nodes[0] = new ESVTNodeUnified();
        nodes[0].setChildMask(0x01);
        nodes[0].setLeafMask(0x01); // parent marks Morton child 0 (node[1]) as a leaf
        nodes[0].setFar(true);
        nodes[0].setChildPtr(0);

        // Child: leaf content node (parent marks it — no leafMask here)
        nodes[1] = new ESVTNodeUnified();

        return ESVTData.builder()
                       .nodes(nodes)
                       .farPointers(new int[]{ 1 })
                       .rootType(0)
                       .maxDepth(2)
                       .leafCount(1)
                       .internalCount(1)
                       .build();
    }

    /**
     * Non-degenerate 6-node far-pointer tree equivalent to the parity test's layout.
     *
     * <ul>
     *   <li>index 0 — root (near, childPtr = 2, children start at index 2)</li>
     *   <li>index 1 — filler leaf</li>
     *   <li>index 2 — far interior node; children start at index 5 → relative = 3;
     *       childPtr = 0 (farPointers[0] = 3)</li>
     *   <li>index 3, 4 — filler leaves</li>
     *   <li>index 5 — first child of the far node (leaf)</li>
     * </ul>
     *
     * This layout ensures relative != absolute (farPointers[0]=3 != 5), catching the
     * root-only coincidence where relative == absolute that {@link #buildFarPointerTree()}
     * would silently hide.
     */
    private ESVTData buildNonDegenerateFarPointerTree() {
        // leafMask convention: bit lives in the PARENT. node[2].leafMask=0x01 marks
        // Morton slot 0 (node[5]) as a leaf. Filler nodes carry no leafMask.
        var nodes = new ESVTNodeUnified[6];

        // Index 0: root — near, childMask=0x01, children start at index 2 (relative = 2)
        // leafMask=0x00: child node[2] is interior
        nodes[0] = new ESVTNodeUnified();
        nodes[0].setChildMask(0x01);
        nodes[0].setChildPtr(2);

        // Index 1: unreachable filler (no parent claims this slot)
        nodes[1] = new ESVTNodeUnified();

        // Index 2: far interior node — far=true, childPtr=0 (index into farPointers[])
        // farPointers[0] = 3 (relative: firstChildIndex=5, currentIndex=2, diff=3)
        // leafMask=0x01: parent marks Morton slot 0 (node[5]) as a leaf
        nodes[2] = new ESVTNodeUnified();
        nodes[2].setChildMask(0x01);
        nodes[2].setLeafMask(0x01); // bit 0: Morton child 0 (node[5]) is a leaf
        nodes[2].setFar(true);
        nodes[2].setChildPtr(0);

        // Indices 3, 4: unreachable fillers
        nodes[3] = new ESVTNodeUnified();
        nodes[4] = new ESVTNodeUnified();

        // Index 5: leaf content node (parent node[2] marks it via leafMask bit 0)
        nodes[5] = new ESVTNodeUnified();

        return ESVTData.builder()
                       .nodes(nodes)
                       .farPointers(new int[]{ 3 })   // relative offset: 5 - 2 = 3
                       .rootType(0)
                       .maxDepth(3)
                       .leafCount(4)
                       .internalCount(2)
                       .build();
    }
}
