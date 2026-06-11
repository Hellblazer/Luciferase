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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-pin tests for ESVO GPU shader and kernel correctness.
 *
 * <p>Ten headless tests pin the post-fix textual contracts for:
 * <ol>
 *   <li>{@code esvo_ray_traversal.cl} — near-branch adds {@code current.nodeIdx} (Luciferase-0bn1q Fix 1)</li>
 *   <li>{@code esvo_ray_traversal.cl} — stale NOTE block absent after fix</li>
 *   <li>{@code raycast.comp} — childPtr extract is masked {@code (childDesc >> 17) & 0x3FFFu} (Fix 2a)</li>
 *   <li>{@code raycast.comp} — descend does NOT use the 2x over-step {@code + ofs * 2u} (Fix 2b)</li>
 *   <li>{@code raycast.comp} — FarPointerBuffer SSBO declaration present (Fix 2c)</li>
 *   <li>{@code raycast.comp} — in-band far read absent (Fix 2c)</li>
 *   <li>{@code raycast.comp} — leafmask polarity: break-on-leaf, not descend-on-leaf (Fix 2d,
 *       Luciferase-7stji review finding)</li>
 *   <li>{@code raycast.comp} — sparse offset counts childmask bits, not the CUDA shifted
 *       non-leaf idiom (Fix 2e, Luciferase-7stji review finding)</li>
 *   <li>{@code esvo_ray_traversal.cl} — rays read via raw float buffer with RAY_STRIDE,
 *       not a misaligned {@code Ray*} struct (Luciferase-gva5z, found during oceo6 hardware run)</li>
 *   <li>{@code esvo_ray_traversal.cl} — traversal stack is private, not {@code __local}
 *       (Luciferase-gva5z)</li>
 * </ol>
 *
 * <p>All tests are headless (no GPU/OpenCL required); they pin the source text
 * loaded via the same {@link ShaderResourceLoader} / resource path used at runtime.
 * Tests 1–2 and 9–10 pin the kernel; tests 3–8 pin the compute shader.
 *
 * <p>Bead: Luciferase-pyqsi (RED phase, TDD). Corresponding fix bead: Luciferase-04ubq.
 * Pins 7–8 added during the Luciferase-7stji stacked review (mask-semantics verification
 * item from the approved design — evidence: Java ESVONodeUnified/OctreeBuilder store
 * leafmask 1=leaf and pack ALL children; the Laine-Karras CUDA reference stores
 * non-leaf 1=descend and packs only non-leaf children).
 */
class ESVOShaderSourcePinTest {

    private static String clSource;
    private static String compSource;

    @BeforeAll
    static void loadSources() {
        // Clear cache so stale process-level cache does not mask on-disk changes during tests
        ShaderResourceLoader.clearCache();
        clSource   = ShaderResourceLoader.loadShader("kernels/esvo_ray_traversal.cl");
        compSource = ShaderResourceLoader.loadShader("shaders/raycast.comp");

        assertNotNull(clSource,   "esvo_ray_traversal.cl must be loadable");
        assertFalse(clSource.isBlank(), "esvo_ray_traversal.cl must not be empty");
        assertNotNull(compSource, "raycast.comp must be loadable");
        assertFalse(compSource.isBlank(), "raycast.comp must not be empty");
    }

    // -------------------------------------------------------------------------
    // esvo_ray_traversal.cl pins
    // -------------------------------------------------------------------------

    /**
     * Pin 1: near branch must add {@code current.nodeIdx} to the raw child pointer.
     *
     * <p>The CPU reference (ESVONodeUnified.getChildIndex) computes:
     * {@code currentNodeIdx + relativeOffset + getChildOffset(childIdx)}.
     * The pre-fix kernel omits {@code current.nodeIdx} in the near case, giving
     * wrong absolute indices for any non-root interior node.
     *
     * <p>Expected post-fix text: {@code childPtr = current.nodeIdx + rawChildPtr;}
     * This test is RED before Fix 1.
     */
    @Test
    void cl_nearBranch_includesNodeIdx() {
        assertTrue(clSource.contains("current.nodeIdx + rawChildPtr"),
                "esvo_ray_traversal.cl near branch must assign "
                + "'childPtr = current.nodeIdx + rawChildPtr;' — pre-fix assigns 'childPtr = rawChildPtr;' "
                + "which is wrong for non-root interior nodes");
    }

    /**
     * Pin 2: stale NOTE block must be absent after fix.
     *
     * <p>The pre-fix kernel contains a self-documenting bug note at :164-167 that says
     * the near path does not add nodeIdx and that "Fixing the near path is tracked separately".
     * After Fix 1 that note must be replaced with an accurate comment.
     *
     * <p>Two sub-assertions:
     * <ul>
     *   <li>Source must NOT contain {@code "pre-existing"}</li>
     *   <li>Source must NOT contain {@code "Fixing the near path is tracked separately"}</li>
     * </ul>
     * Both are RED before Fix 1.
     */
    @Test
    void cl_staleNote_absent() {
        assertFalse(clSource.contains("pre-existing"),
                "esvo_ray_traversal.cl must not contain the stale 'pre-existing' NOTE after fix");
        assertFalse(clSource.contains("Fixing the near path is tracked separately"),
                "esvo_ray_traversal.cl must not contain the stale 'Fixing the near path is tracked separately' "
                + "text after fix — the near path IS fixed by Luciferase-04ubq");
    }

    /**
     * Pin 9: kernel must read rays from the raw float buffer, not a {@code Ray*} struct pointer.
     *
     * <p>The host ({@code AbstractOpenCLRenderer}) packs rays tightly at 8 floats / 32 bytes.
     * An OpenCL struct with {@code float3} members has 16-byte member alignment (48-byte
     * stride), so {@code rays[gid]} on a {@code __global const Ray*} reads garbage for
     * every gid ≥ 1 — the pre-fix kernel produced zero correct GPU hits, masked by vacuous
     * assertions. Post-fix the kernel takes {@code __global const float* rays} and decodes
     * via {@code loadRayFromBuffer} with explicit {@code RAY_STRIDE} indexing (the
     * esvt_ray_traversal.cl pattern).
     *
     * <p>This test is RED before the Luciferase-gva5z fix.
     */
    @Test
    void cl_rayAccess_usesFloatBufferStride() {
        assertFalse(clSource.contains("__global const Ray* rays"),
                "esvo_ray_traversal.cl must not take '__global const Ray* rays' — float3 struct "
                + "alignment makes the kernel stride 48B vs the host's tightly packed 32B rays");
        assertTrue(clSource.contains("loadRayFromBuffer"),
                "esvo_ray_traversal.cl must decode rays via loadRayFromBuffer with explicit "
                + "RAY_STRIDE indexing (esvt_ray_traversal.cl pattern)");
        assertTrue(clSource.contains("#define RAY_STRIDE 8"),
                "esvo_ray_traversal.cl must define RAY_STRIDE 8 matching the host's "
                + "RAY_SIZE_FLOATS = 8 packing");
    }

    /**
     * Pin 10: traversal stack must be PRIVATE, not {@code __local}.
     *
     * <p>The pre-fix kernel declared {@code __local StackEntry stack[MAX_TRAVERSAL_DEPTH]}
     * — one stack shared by all threads of the workgroup (LOCAL_WORK_SIZE=64) with no
     * per-thread offset, a data race corrupting every traversal. Post-fix the stack is a
     * private per-work-item array (the esvt_ray_traversal.cl pattern).
     *
     * <p>This test is RED before the Luciferase-gva5z fix.
     */
    @Test
    void cl_traversalStack_isPrivate() {
        assertFalse(clSource.contains("__local StackEntry"),
                "esvo_ray_traversal.cl traversal stack must not be __local — a workgroup-shared "
                + "stack with no per-thread offset is a data race across the 64-thread workgroup");
    }

    // -------------------------------------------------------------------------
    // raycast.comp pins
    // -------------------------------------------------------------------------

    /**
     * Pin 3: childPtr extract must be masked with {@code & 0x3FFFu}.
     *
     * <p>Pre-fix line :172: {@code uint childPtr = childDesc >> 17;} leaks bit 31 (valid flag)
     * into childPtr when valid=1, producing a +0x4000 bias.
     * Post-fix: {@code uint childPtr = (childDesc >> 17) & 0x3FFFu;} (14-bit mask, correct for ESVO).
     *
     * <p>This test is RED before Fix 2a.
     */
    @Test
    void raycast_childPtrExtract_isMasked() {
        assertTrue(compSource.contains("(childDesc >> 17) & 0x3FFFu"),
                "raycast.comp childPtr extract must apply the 14-bit mask: '(childDesc >> 17) & 0x3FFFu' — "
                + "pre-fix has unmasked 'childDesc >> 17' which leaks bit 31");
    }

    /**
     * Pin 4: descend must NOT contain {@code + ofs * 2u} stride.
     *
     * <p>Pre-fix line :250: {@code parentIdx = parentIdx + ofs * 2u;} is wrong because
     * {@code nodes[]} is a {@code uvec2[]} (one element = one node = 8 bytes) so the
     * index is already in node-units; multiplying by 2 produces a 2x over-step.
     * Post-fix: {@code parentIdx = parentIdx + ofs;}.
     *
     * <p>This test is RED before Fix 2b.
     */
    @Test
    void raycast_descend_noTwoUStride() {
        assertFalse(compSource.contains("+ ofs * 2u"),
                "raycast.comp descend block must not contain '+ ofs * 2u' — "
                + "uvec2[] is already node-unit indexed; the *2u stride is a 2x over-step bug");
    }

    /**
     * Pin 5: FarPointerBuffer SSBO declaration must be present.
     *
     * <p>Pre-fix: no FarPointerBuffer SSBO in raycast.comp.
     * Post-fix: {@code layout(std430, binding = 3) readonly buffer FarPointerBuffer { int farPointers[]; };}
     * The test checks for both the block name and the field name.
     *
     * <p>This test is RED before Fix 2c.
     */
    @Test
    void raycast_farPointers_ssbo_present() {
        assertTrue(compSource.contains("buffer FarPointerBuffer"),
                "raycast.comp must declare 'buffer FarPointerBuffer' SSBO — absent pre-fix");
        assertTrue(compSource.contains("farPointers[]"),
                "raycast.comp FarPointerBuffer must contain 'farPointers[]' field — absent pre-fix");
    }

    /**
     * Pin 6: in-band far read via {@code octree.nodes[parentIdx + ofs} must be absent.
     *
     * <p>Pre-fix line :245: {@code ofs = uint(octree.nodes[parentIdx + ofs * 2u].x);} reads
     * the far offset inline from the node buffer — wrong because the CPU path uses a side
     * table ({@code farPointers[]}).
     * Post-fix: far case reads via {@code ofs = uint(farPointers[ofs]);}.
     *
     * <p>This test is RED before Fix 2c.
     */
    @Test
    void raycast_inbandFarRead_absent() {
        assertFalse(compSource.contains("octree.nodes[parentIdx + ofs"),
                "raycast.comp must not contain the in-band far read 'octree.nodes[parentIdx + ofs' — "
                + "post-fix must use 'farPointers[ofs]' side-table indirection instead");
    }

    /**
     * Pin 7: leafmask polarity — descend gate must break on a set leaf bit, not descend.
     *
     * <p>The Java encoder ({@code ESVONodeUnified}/{@code OctreeBuilder}) stores bits 0-7
     * as a leafmask with 1 = leaf. The CUDA reference stores a NON-leaf mask there
     * (1 = descend). The pre-fix shader copied the CUDA polarity, descending INTO leaf
     * children (via garbage child pointers) and terminating at internal children.
     * Post-fix: inside the t-span gate, a set bit 7 of the shifted descriptor breaks
     * (leaf voxel hit); descend happens only when the bit is clear.
     *
     * <p>This test is RED before Fix 2d (Luciferase-7stji).
     */
    @Test
    void raycast_leafBit_breaksNotDescends() {
        assertTrue(compSource.contains("break; // leaf voxel hit"),
                "raycast.comp must break (register a hit) when the shifted leafmask bit is set — "
                + "Java leafmask is 1=leaf, unlike the CUDA non-leaf mask");
        assertFalse(compSource.contains("if ((childMasks & 0x0080u) != 0u && t_min <= tv_max)"),
                "raycast.comp must not use the pre-fix combined descend gate "
                + "'(childMasks & 0x0080u) != 0u && t_min <= tv_max' — with Java leafmask "
                + "semantics that descends into leaves and never descends into internal nodes");
    }

    /**
     * Pin 8: sparse child offset must count CHILDMASK bits, not the CUDA shifted idiom.
     *
     * <p>The CUDA reference packs child descriptors for non-leaf children only and counts
     * its non-leaf mask via {@code popc8(child_masks & 0x7F)}. The Java encoder packs ALL
     * existing children consecutively ({@code ESVONodeUnified.getChildOffset} counts
     * childmask bits below the slot). The pre-fix shader used the CUDA idiom against
     * Java-encoded data, computing wrong sparse offsets.
     *
     * <p>This test is RED before Fix 2e (Luciferase-7stji).
     */
    @Test
    void raycast_sparseOffset_usesChildMask() {
        assertFalse(compSource.contains("popc8(childMasks & 0x7Fu)"),
                "raycast.comp must not compute the sparse child offset with the CUDA idiom "
                + "'popc8(childMasks & 0x7Fu)' — that counts leafmask bits under the Java encoding");
        assertTrue(compSource.contains("bitCount(childMask8 &"),
                "raycast.comp sparse child offset must count childmask bits below the slot "
                + "('bitCount(childMask8 & ...)') to match ESVONodeUnified.getChildOffset");
    }
}
