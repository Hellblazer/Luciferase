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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;  // used in buildNearOnlyOctree / buildFarPointerOctree
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVOOpenCLRenderer far-pointer wiring (Luciferase-7wzml.222).
 *
 * <p>Far pointers are now fully wired:
 * <ul>
 *   <li>{@code esvo_ray_traversal.cl} receives a {@code farPointers[]} buffer (arg 2) and
 *       checks {@code FAR_FLAG_BIT} (bit 16) in the traversal loop.</li>
 *   <li>{@link ESVOOpenCLRenderer#uploadDataBuffers} uploads the far-pointer table as
 *       a second {@code cl_mem} buffer alongside the node buffer.</li>
 *   <li>When no far pointers are present a minimal 4-byte placeholder buffer is still
 *       uploaded so the kernel arg slot is always bound.</li>
 * </ul>
 *
 * <p>All headless tests are CPU-side (no GPU/OpenCL required): they exercise the
 * {@code octreeToByteBuffer} conversion path via reflection.  The GPU parity test is
 * gated behind {@code RUN_GPU_TESTS=true}.
 */
class ESVOOpenCLRendererFarPointerTest {

    // -------------------------------------------------------------------------
    // Headless CPU-side correctness tests
    // -------------------------------------------------------------------------

    /**
     * A near-only octree (no far pointers) must NOT throw.
     */
    @Test
    void nearOnlyOctree_doesNotThrow() throws Exception {
        var data = buildNearOnlyOctree();
        var renderer = new ESVOOpenCLRenderer(64, 64);

        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> {
            var buf = method.invoke(renderer, data);
            assertNotNull(buf, "ByteBuffer must be non-null for near-only octree");
        }, "Near-only octree must not throw");
    }

    /**
     * An octree with far pointers must also succeed — the guard added in .160 is removed.
     */
    @Test
    void farPointerOctree_doesNotThrow() throws Exception {
        var data = buildFarPointerOctree();
        var renderer = new ESVOOpenCLRenderer(64, 64);

        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> {
            var buf = method.invoke(renderer, data);
            assertNotNull(buf, "ByteBuffer must be non-null for far-pointer octree");
        }, "Far-pointer octree must not throw — far pointers are now supported");
    }

    /**
     * Empty far-pointer array (length 0) must NOT throw.
     */
    @Test
    void emptyFarPointerArray_doesNotThrow() throws Exception {
        var data = buildNearOnlyOctree();
        data.setFarPointers(new int[0]);

        var renderer = new ESVOOpenCLRenderer(64, 64);
        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(renderer, data),
                "Empty far-pointer array must not throw");
    }

    /**
     * Verify the node ByteBuffer produced for a near-only octree has the expected layout:
     * 2 nodes × 8 bytes each = 16 bytes, with correct childDescriptor values.
     */
    @Test
    void nearOnlyOctree_byteBufferLayout() throws Exception {
        var data = buildNearOnlyOctree();
        var renderer = new ESVOOpenCLRenderer(64, 64);

        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        var buf = (ByteBuffer) method.invoke(renderer, data);
        assertNotNull(buf);

        // 2 nodes × (childDescriptor int + contourDescriptor int) = 16 bytes
        assertEquals(2 * ESVONodeUnified.SIZE_BYTES, buf.limit(),
                "Near-only octree with 2 nodes should produce " + (2 * ESVONodeUnified.SIZE_BYTES) + " bytes");

        buf.order(ByteOrder.nativeOrder());
        // Root node (index 0): childDescriptor should have FAR_FLAG_BIT=0, childPtr=1
        // Constants mirror ESVONodeUnified: FAR_FLAG_BIT=0x10000, CHILD_PTR_SHIFT=17
        int rootDescriptor = buf.getInt(0);
        assertEquals(0, rootDescriptor & 0x10000,
                "Root node must not have FAR_FLAG_BIT (bit 16) set");
        assertEquals(1, (rootDescriptor >> 17) & 0x3FFF,
                "Root childPtr must be 1");
    }

    /**
     * Verify the node ByteBuffer for a far-pointer octree has the FAR_FLAG_BIT set in the
     * root descriptor, confirming the far flag survives serialisation.
     */
    @Test
    void farPointerOctree_byteBufferHasFarFlagSet() throws Exception {
        var data = buildFarPointerOctree();
        var renderer = new ESVOOpenCLRenderer(64, 64);

        Method method = ESVOOpenCLRenderer.class.getDeclaredMethod("octreeToByteBuffer", ESVOOctreeData.class);
        method.setAccessible(true);

        var buf = (ByteBuffer) method.invoke(renderer, data);
        assertNotNull(buf);

        buf.order(ByteOrder.nativeOrder());
        // Root node (index 0): FAR_FLAG_BIT (bit 16 = 0x10000) must be set
        int rootDescriptor = buf.getInt(0);
        assertNotEquals(0, rootDescriptor & 0x10000,
                "Root descriptor must have FAR_FLAG_BIT (bit 16) set for far-pointer octree");
    }

    // -------------------------------------------------------------------------
    // GPU parity test — gated behind RUN_GPU_TESTS=true
    // -------------------------------------------------------------------------

    /**
     * Headless CPU-side consistency test: NEAR interior node at a NON-zero array index.
     *
     * <p>This pins the CPU reference for the near-path case that the pre-fix kernel
     * ({@code childPtr = rawChildPtr}) handles incorrectly for non-root nodes.
     *
     * <p>Layout:
     * <ul>
     *   <li>index 0 — root (leaf, not the node under test)</li>
     *   <li>index 1 — padding leaf</li>
     *   <li>index 2 — padding leaf</li>
     *   <li>index 3 — near interior node; childPtr = 2 (relative offset), so children start at 3+2=5</li>
     *   <li>index 4 — padding leaf</li>
     *   <li>index 5 — first child of the near node</li>
     * </ul>
     *
     * <p>The CPU reference {@link ESVONodeUnified#getChildIndex(int, int, int[])} must
     * resolve to 5: {@code currentNodeIdx(3) + relativeOffset(2) + popcount(0) = 5}.
     *
     * <p>This test passes NOW (the CPU path is correct) and pins the CPU near-path
     * reference headlessly. The GPU-gated {@code nearAndFarNodeAtNonZeroIndex_gpuParity}
     * below exercises the same near-non-root case on hardware with its own tree and
     * CPU reference assertions.
     */
    @Test
    void nearNodeAtNonZeroIndex_cpuResolutionCorrect() {
        var data = new ESVOOctreeData(1024);

        // Root — leaf, not the node under test
        data.setNode(0, new ESVONodeUnified((byte) 0x00, (byte) 0x00, false, 0, (byte) 0, 1));
        // index 1: leaf filler
        data.setNode(1, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1));
        // index 2: leaf filler
        data.setNode(2, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1));
        // index 3: near interior node — childMask=0x01 (one child), far=false, childPtr=2
        // children start at 3 + 2 = 5
        var nearNode = new ESVONodeUnified((byte) 0x01, (byte) 0x01, false, 2, (byte) 0, 0);
        data.setNode(3, nearNode);
        // index 4: leaf filler
        data.setNode(4, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1));
        // index 5: child of the near node
        data.setNode(5, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1));

        ESVONodeUnified node = data.getNode(3);
        assertFalse(node.isFar(), "Node at index 3 must NOT have isFar()=true (near path)");
        assertEquals(2, node.getChildPtr(),
                "nearNode childPtr must be 2 (relative offset from index 3 to children at 5)");

        // CPU reference: currentNodeIdx(3) + relativeOffset(2) + getChildOffset(0)=0 = 5
        int resolvedIndex = node.getChildIndex(0, 3, null);
        assertEquals(5, resolvedIndex,
                "CPU getChildIndex for near node at index 3, childIdx=0 must resolve to 5 "
                + "(currentNodeIdx=3 + relativeOffset=2 + popcount=0). "
                + "This is the value the GPU must also produce after Fix 1 (Luciferase-04ubq).");
    }

    /**
     * Headless CPU-side consistency test: far node at a NON-zero array index.
     *
     * <p>This is the structural case the original {@code buildFarPointerOctree} octree
     * hides (its only far node is the root at index 0, so relative==absolute and the
     * N>0 bug is invisible).  Here we place an interior far node at index 2 whose
     * children start at index 5 — relative offset = 5 - 2 = 3.  We verify:
     * <ol>
     *   <li>The far-pointer table stores the RELATIVE offset (3, not 5).</li>
     *   <li>{@link ESVONodeUnified#getChildIndex} (the CPU reference) resolves to the
     *       correct absolute child index: 2 (current) + 3 (relative) + 0 (popcount for
     *       the first present child) = 5.</li>
     * </ol>
     * This validates the byte-buffer far encoding and the CPU reference without requiring
     * a GPU.  The corresponding GPU fix (adding {@code current.nodeIdx} in the far branch
     * of {@code esvo_ray_traversal.cl}) cannot be verified headless — that is left for
     * the GPU-gated test below.
     */
    @Test
    void farNodeAtNonZeroIndex_cpuResolutionCorrect() {
        // Layout:
        //   index 0 — root (near pointer, childPtr = relative 2, first child at index 2)
        //   index 1 — padding / sibling leaf
        //   index 2 — interior far node, children start at index 5 → relative offset = 5 - 2 = 3
        //   index 3, 4 — padding / siblings
        //   index 5 — first child of the far node (leaf)

        var data = new ESVOOctreeData(1024);

        // Root: near, single child at index 2 → relativeOffset = 2
        var root = new ESVONodeUnified((byte) 0x01, (byte) 0x01, false, 2, (byte) 0, 0);
        data.setNode(0, root);

        // Index 1: leaf filler
        var filler1 = new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1);
        data.setNode(1, filler1);

        // Index 2: far interior node — childMask=0x01 (one child), far=true, childPtr=0 (into farPointers[])
        // farPointers[0] = 3 (relative: firstChildIndex=5, currentIndex=2, diff=3)
        var farNode = new ESVONodeUnified((byte) 0x01, (byte) 0x01, true, 0, (byte) 0, 0);
        data.setNode(2, farNode);

        // Indices 3,4: leaf fillers
        data.setNode(3, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1));
        data.setNode(4, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1));

        // Index 5: child leaf of the far node
        data.setNode(5, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0, 1));

        // Far-pointer table: relative offset for the node at index 2 reaching children at index 5
        int relativeOffset = 5 - 2; // = 3
        data.setFarPointers(new int[] { relativeOffset });

        // --- Verify far-pointer encoding ---
        int[] fp = data.getFarPointers();
        assertNotNull(fp, "farPointers must be set");
        assertEquals(1, fp.length, "Exactly one far pointer expected");
        assertEquals(3, fp[0],
                "farPointers[0] must store the RELATIVE offset (firstChildIndex - currentIndex = 5 - 2 = 3)");

        // --- Verify CPU getChildIndex for the far node at index 2 ---
        // farNode.childMask = 0x01 (child 0 present), childIdx=0, popcount = 0
        // Expected: currentNodeIdx(2) + relativeOffset(3) + getChildOffset(0)=0 = 5
        ESVONodeUnified node = data.getNode(2);
        assertTrue(node.isFar(), "Node at index 2 must have isFar()=true");
        int resolvedIndex = node.getChildIndex(0, 2, fp);
        assertEquals(5, resolvedIndex,
                "CPU getChildIndex for far node at index 2, childIdx=0 must resolve to 5 "
                + "(currentNodeIdx=2 + relativeOffset=3 + popcount=0)");
    }

    /**
     * GPU renders a far-pointer octree without throwing.
     *
     * <p><strong>Naming note</strong>: this test only asserts that the GPU call completes
     * without exception and that the output buffer has the expected size.  It does NOT
     * compare GPU pixel output to a CPU reference — that would require a deterministic
     * scene and per-pixel comparison, which is deferred (see Luciferase-7wzml follow-up).
     * The far node used here is the root (index 0), so relative==absolute and the N>0
     * GPU correctness path is not exercised.  The headless test
     * {@code farNodeAtNonZeroIndex_cpuResolutionCorrect} above covers the structural case
     * CPU-side; GPU parity at N>0 requires hardware and a follow-up bead.
     *
     * <p>Run with: {@code RUN_GPU_TESTS=true mvn test -pl render -Dtest=ESVOOpenCLRendererFarPointerTest#farPointerOctree_gpuRendersWithoutThrowing}
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true",
            disabledReason = "GPU test requires a real OpenCL device — set RUN_GPU_TESTS=true")
    void farPointerOctree_gpuRendersWithoutThrowing() {
        // This test requires OpenCL — guard gracefully even inside the env-var gate
        if (!ESVOOpenCLRenderer.isOpenCLAvailable()) {
            System.out.println("OpenCL not available on this machine — skipping GPU far-pointer render test");
            return;
        }

        var data = buildFarPointerOctree();

        try (var renderer = new ESVOOpenCLRenderer(64, 64)) {
            renderer.initialize();
            renderer.uploadData(data);

            // Render a single frame from a simple camera position
            var cameraPos = new javax.vecmath.Vector3f(0.5f, 0.5f, 2.0f);
            var lookAt = new javax.vecmath.Vector3f(0.5f, 0.5f, 0.5f);
            assertDoesNotThrow(() -> renderer.renderFrame(cameraPos, lookAt, 60.0f),
                    "GPU traversal of far-pointer octree must not throw");

            // Verify output image is non-null and has correct dimensions
            var outputImage = renderer.getOutputImage();
            assertNotNull(outputImage, "Output image must be non-null after rendering");
            assertEquals(64 * 64 * 4, outputImage.limit(),
                    "Output image must be 64×64 RGBA = 16384 bytes");

            System.out.println("GPU far-pointer render test: completed without throwing (" +
                               data.getNodeCount() + " nodes, " +
                               (data.getFarPointers() != null ? data.getFarPointers().length : 0) +
                               " far pointer(s)). NOTE: no CPU-vs-GPU pixel comparison performed.");
        }
    }

    /**
     * GPU render parity: an octree requiring correct near AND far resolution at non-root indices,
     * with BOTH interior nodes reachable from the root and each chaining to its own hittable leaf.
     *
     * <p>Tree (all interior nodes reachable; the two subtrees occupy disjoint x-octants):
     * <ul>
     *   <li>index 0 — root: near, childMask=0x03 (slots 0 and 1), childPtr=1.
     *       Slot 0 → index 1, slot 1 → index 2.</li>
     *   <li>index 1 — FAR interior node at non-zero index (slot 0: x∈[0,0.5)):
     *       childMask=0x01, far=true, childPtr=0; farPointers[0]=2 → child at 1+2=3.</li>
     *   <li>index 2 — NEAR interior node at non-zero index (slot 1: x∈[0.5,1)):
     *       childMask=0x01, far=false, childPtr=2 → child at 2+2=4.</li>
     *   <li>index 3 — leaf (contourPtr=42 → hit), region x∈[0,0.25), y,z∈[0,0.25).</li>
     *   <li>index 4 — leaf (contourPtr=43 → hit), region x∈[0.5,0.75), y,z∈[0,0.25).</li>
     * </ul>
     *
     * <p><b>Why this distinguishes the fixed kernel from the pre-fix kernel</b>: with the
     * pre-fix near branch ({@code childPtr = rawChildPtr}, missing {@code current.nodeIdx}),
     * the near node at index 2 resolves its child to raw 2 = itself, self-loops to maxDepth
     * and never reaches the leaf at index 4 — so no hit is produced anywhere in the near
     * subtree's half of the image. The far path (fixed in 7wzml.222) still hits leaf@3 in
     * the other half. Asserting hit pixels in BOTH image halves therefore fails on the
     * pre-fix kernel and passes on the fixed one (an any-hit assertion would be masked by
     * the far-path hit).
     *
     * <p><b>Pixel mapping</b>: camera at (0.5,0.5,2) looking at (0.5,0.5,0.5) down −z;
     * AbstractOpenCLRenderer.generateRays uses ndcX = 2x/W−1, so world-x maps monotonically
     * to pixel-x. The far leaf (x&lt;0.25) and near leaf (x≥0.5) land in opposite image
     * halves; the both-halves assertion is also invariant under a horizontal flip.
     *
     * <p><b>Hit detection</b>: misses are NOT zero — ESVOOpenCLRenderer.computePixelColor
     * writes background RGB (20,20,30) with alpha 255 on miss. A pixel is a hit iff its RGB
     * differs from the background triple.
     *
     * <p>Gated behind {@code RUN_GPU_TESTS=true} — do NOT run in headless CI.
     * Run locally: {@code RUN_GPU_TESTS=true mvn test -pl render -Dtest=ESVOOpenCLRendererFarPointerTest#nearAndFarNodeAtNonZeroIndex_gpuParity}
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true",
            disabledReason = "GPU test requires a real OpenCL device — set RUN_GPU_TESTS=true; bead oceo6")
    void nearAndFarNodeAtNonZeroIndex_gpuParity() {
        if (!ESVOOpenCLRenderer.isOpenCLAvailable()) {
            System.out.println("OpenCL not available — skipping nearAndFarNodeAtNonZeroIndex_gpuParity");
            return;
        }

        var data = new ESVOOctreeData(1024);

        // Index 0: root — near, children in slots 0 and 1 (childMask=0x03), childPtr=1.
        // slot 0: 0 + 1 + popcount(0x03 & 0x00)=0 → index 1
        // slot 1: 0 + 1 + popcount(0x03 & 0x01)=1 → index 2
        var root = new ESVONodeUnified((byte) 0x00, (byte) 0x03, false, 1, (byte) 0, 0);
        data.setNode(0, root);

        // Index 1: FAR interior node — childMask=0x01, far=true, childPtr=0 (index into
        // farPointers[]). farPointers[0]=2 → child at 1+2+0=3. leafMask=0x01: its one
        // child is a leaf (OctreeBuilder convention).
        var farNode = new ESVONodeUnified((byte) 0x01, (byte) 0x01, true, 0, (byte) 0, 0);
        data.setNode(1, farNode);

        // Index 2: NEAR interior node at non-zero index — childMask=0x01, far=false,
        // childPtr=2 (relative) → child at 2+2+0=4. THIS is the node the pre-fix kernel
        // resolves wrongly (raw 2 → itself instead of 4).
        var nearNode = new ESVONodeUnified((byte) 0x01, (byte) 0x01, false, 2, (byte) 0, 0);
        data.setNode(2, nearNode);

        // Index 3: leaf hit — child of the FAR node. contourPtr != 0 → hit (kernel hit
        // condition: childMask==0 && contourPtr!=0). leafMask=0xFF: leaf-node convention.
        data.setNode(3, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0xFF, 42));

        // Index 4: leaf hit — child of the NEAR node. contourPtr != 0 → hit.
        data.setNode(4, new ESVONodeUnified((byte) 0xFF, (byte) 0x00, false, 0, (byte) 0xFF, 43));

        // Far-pointer table: farPointers[0] = 2 (relative offset from index 1 to index 3).
        data.setFarPointers(new int[] { 2 });

        // Tree depth: root (0) → interior (1) → leaves (2). The kernel treats
        // current.scale >= maxDepth as a forced leaf, so maxDepth must exceed the leaf
        // depth or every ray terminates at the root with no hit (maxDepth defaults to 0
        // on hand-built ESVOOctreeData — found during oceo6 hardware verification).
        data.setMaxDepth(3);

        // --- CPU reference (gospel): verify child resolution for every edge of the tree ---
        int[] fp = data.getFarPointers();
        assertEquals(1, data.getNode(0).getChildIndex(0, 0, fp),
                "CPU root slot 0 must resolve to 1 (far interior node)");
        assertEquals(2, data.getNode(0).getChildIndex(1, 0, fp),
                "CPU root slot 1 must resolve to 2 (near interior node)");
        assertEquals(3, data.getNode(1).getChildIndex(0, 1, fp),
                "CPU far-path: farNode@1 must resolve child to 3 (1 + farPointers[0]=2 + 0)");
        assertEquals(4, data.getNode(2).getChildIndex(0, 2, fp),
                "CPU near-path: nearNode@2 must resolve child to 4 (2 + 2 + 0) — "
                + "the pre-fix GPU kernel resolves raw 2 (itself) instead");

        // --- GPU render ---
        try (var renderer = new ESVOOpenCLRenderer(64, 64)) {
            renderer.initialize();
            renderer.uploadData(data);

            var cameraPos = new javax.vecmath.Vector3f(0.5f, 0.5f, 2.0f);
            var lookAt    = new javax.vecmath.Vector3f(0.5f, 0.5f, 0.5f);
            assertDoesNotThrow(() -> renderer.renderFrame(cameraPos, lookAt, 60.0f),
                    "GPU traversal must not throw for near+far non-root octree");

            var outputImage = renderer.getOutputImage();
            assertNotNull(outputImage, "Output image must be non-null");
            assertEquals(64 * 64 * 4, outputImage.limit(),
                    "Output image must be 64x64 RGBA = 16384 bytes");

            // Count hit pixels per image half. Background (miss) RGB is (20,20,30) per
            // ESVOOpenCLRenderer.computePixelColor; anything else is a hit.
            int hitsLeft = 0, hitsRight = 0;
            outputImage.rewind();
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int base = (y * 64 + x) * 4;
                    int r = outputImage.get(base)     & 0xFF;
                    int g = outputImage.get(base + 1) & 0xFF;
                    int b = outputImage.get(base + 2) & 0xFF;
                    boolean hit = !(r == 20 && g == 20 && b == 30);
                    if (hit) {
                        if (x < 32) hitsLeft++;
                        else        hitsRight++;
                    }
                }
            }

            // The far leaf (x<0.25) and near leaf (x in [0.5,0.75)) occupy opposite image
            // halves. The pre-fix kernel hits only the far leaf → one half stays empty.
            assertTrue(hitsLeft > 0 && hitsRight > 0,
                    "GPU render must produce hit pixels in BOTH image halves "
                    + "(far-path leaf and near-path leaf are in opposite halves). "
                    + "hitsLeft=" + hitsLeft + ", hitsRight=" + hitsRight + " — a half with "
                    + "zero hits means that traversal path (near non-root child resolution "
                    + "or far-pointer table resolution) is broken on the GPU");

            System.out.println("[nearAndFarNodeAtNonZeroIndex_gpuParity] PASS — GPU hits in both halves: "
                               + "left=" + hitsLeft + " (far subtree), right=" + hitsRight
                               + " (near subtree). farPointers[0]=2; far child@3, near child@4. "
                               + "Nodes=" + data.getNodeCount() + ", fp.length=" + fp.length);
        }
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
        // Depth metadata: hand-built data defaults to maxDepth=0, which makes the kernel
        // treat the root as a forced leaf if this helper is ever used in a GPU test.
        data.setMaxDepth(2);
        return data;
    }

    private ESVOOctreeData buildFarPointerOctree() {
        var data = new ESVOOctreeData(1024);
        // Root node (index 0) with far flag set: isFar=true, childPtr=0 is an index into farPointers[].
        // farPointers[0] = 1, which is the RELATIVE offset (firstChildIndex=1, currentIndex=0, diff=1).
        // For root at index 0, relative == absolute, so this is both 1 as a relative offset and
        // 1 as an absolute array index — the two are coincidentally equal here.
        var root = new ESVONodeUnified((byte) 0x01, (byte) 0x01, true, 0, (byte) 0, 0);
        data.setNode(0, root);
        var child = new ESVONodeUnified((byte) 0x00, (byte) 0x00, false, 0, (byte) 0, 1);
        data.setNode(1, child);
        // Far-pointer table: farPointers[0] = 1 (relative offset from root=0 to child=1).
        data.setFarPointers(new int[] { 1 });
        // Depth metadata: root → leaf at depth 1 (hand-built data defaults to maxDepth=0,
        // which makes the kernel treat the root as a forced leaf).
        data.setMaxDepth(2);
        return data;
    }
}
