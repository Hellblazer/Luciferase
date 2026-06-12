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

import com.hellblazer.luciferase.esvo.gpu.ShaderResourceLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-pin tests for ESVT GPU shader and kernel correctness.
 *
 * <p>Headless tests pinning the textual contracts of {@code raycast_esvt.comp} and
 * {@code esvt_ray_traversal.cl}, loaded via the same {@link ShaderResourceLoader} /
 * resource path used at runtime (no GPU/OpenCL required).
 *
 * <p>Two pin generations:
 * <ul>
 *   <li><b>jk5tk/g51tc parity pins</b> (Pins 1-11): all-8 Morton scan, CHILD_ORDER removal,
 *       global min-t bestT, no first-hit return, Morton→Bey conversion, root-without-leaf MISS,
 *       real leaf normals. Pins 1, 3, 5b, and 7 were revised in place by 2s3jc (see their javadoc)
 *       — the all-8 loop survives as the front-to-back COLLECT scan, stack_siblingPos is
 *       repurposed as the packed sorted-order slot, and the keep-scanning marker count doubled
 *       (fresh + resume paths).</li>
 *   <li><b>2s3jc front-to-back pins</b> (Pins 12-19): tandem insertion sort present in both
 *       shaders with the exact CPU comparator, sortedOrder StackEntry field (.cl), packed
 *       sorted-order slot + per-thread offset + no-6th-shared-array (.comp), and the prune-break
 *       marker at both implementations' visit loops.</li>
 * </ul>
 *
 * <p>Beads: Luciferase-g51tc (parity pins), Luciferase-y3l06 (front-to-back pin revision).
 */
class ESVTShaderSourcePinTest {

    private static String clSource;
    private static String compSource;

    @BeforeAll
    static void loadSources() {
        // Clear cache so stale process-level cache does not mask on-disk changes during tests
        ShaderResourceLoader.clearCache();
        clSource   = ShaderResourceLoader.loadShader("kernels/esvt_ray_traversal.cl");
        compSource = ShaderResourceLoader.loadShader("shaders/raycast_esvt.comp");

        assertNotNull(clSource,   "esvt_ray_traversal.cl must be loadable");
        assertFalse(clSource.isBlank(), "esvt_ray_traversal.cl must not be empty");
        assertNotNull(compSource, "raycast_esvt.comp must be loadable");
        assertFalse(compSource.isBlank(), "raycast_esvt.comp must not be empty");
    }

    // -------------------------------------------------------------------------
    // raycast_esvt.comp pins (P3 Fix 1)
    // -------------------------------------------------------------------------

    /**
     * Pin 1 (revised by 2s3jc): all-8 Morton scan must survive as the front-to-back COLLECT loop.
     *
     * <p>The pre-jk5tk shader iterated only 4 entry-face children via
     * {@code for (int pos = siblingPos; pos < 4; pos++)} with an indirection through
     * {@code CHILD_ORDER[parentType][entryFace][pos]}, missing up to 4 intersecting children.
     * jk5tk replaced it with the all-8 Morton visit loop. 2s3jc restructured that loop into
     * COLLECT ({@code for (int childIdx = 0; childIdx < 8; childIdx++)} gathering candidates)
     * + sorted VISIT — the all-8 scan survives as the collection pass. The {@code pos < 4}
     * pattern must never return.
     */
    @Test
    void comp_childLoop_iteratesAllEightChildren() {
        assertTrue(compSource.contains("childIdx < 8"),
                "raycast_esvt.comp traversal loop must iterate all 8 Morton children "
                + "'for (int childIdx = siblingPos; childIdx < 8; childIdx++)' — "
                + "pre-fix iterates only 4 entry-face children via 'pos < 4'");
        assertFalse(compSource.contains("pos < 4"),
                "raycast_esvt.comp must not contain the old 4-child positional loop 'pos < 4' — "
                + "post-fix replaces it with the all-8 Morton scan 'childIdx < 8'");
    }

    /**
     * Pin 2: CHILD_ORDER constant must be absent.
     *
     * <p>The pre-fix shader declared {@code const int CHILD_ORDER[6][4][4]} at lines 77-90
     * (and a comment block at 60-74). After the all-8 scan replaces the positional loop, this
     * table is unused dead code and must be removed to prevent future callers from mistaking it
     * for the canonical traversal order.
     *
     * <p>This test is RED before P3 Fix 1b.
     */
    @Test
    void comp_childOrderConstant_absent() {
        assertFalse(compSource.contains("CHILD_ORDER"),
                "raycast_esvt.comp must not declare or reference 'CHILD_ORDER' — "
                + "the 4-child entry-face table is dead code after the all-8 Morton scan replaces the loop");
    }

    /**
     * Pin 3 (revised by 2s3jc): shared siblingPos stack must be present — now as the packed
     * front-to-back slot.
     *
     * <p>jk5tk added {@code shared int stack_siblingPos[64 * CAST_STACK_DEPTH];} storing
     * {@code childIdx + 1} for resume-after-pop. 2s3jc repurposed the slot (NO 6th shared
     * array — 32 KiB shared-memory budget): bits 31-28 = sortedPos resume cursor, bits 27-24 =
     * sorted child count, bits 23-0 = eight 3-bit Morton childIdx slots in sorted visit order.
     * The array itself must remain — without it, pop restarts the child visit from slot 0,
     * re-scanning already-visited children.
     */
    @Test
    void comp_siblingPosStack_present() {
        assertTrue(compSource.contains("stack_siblingPos"),
                "raycast_esvt.comp must declare 'stack_siblingPos' shared array for correct "
                + "resume-after-pop — pre-fix resets siblingPos=0 on every pop, re-scanning "
                + "already-visited children and potentially missing later siblings");
    }

    /**
     * Pin 4: global min-t tracking — {@code bestT} variable must be declared and compared.
     *
     * <p>The pre-fix shader returned immediately on the FIRST valid leaf or LOD hit
     * ({@code return;} at two sites inside the traversal while loop). Correct DFS must scan
     * all children, update a global {@code bestT}, and return only the closest hit after stack
     * exhaustion. Post-fix declares {@code float bestT} outside the while loop and updates it
     * at LOD/leaf sites with a {@code < bestT} guard.
     *
     * <p>This test is RED before P3 Fix 1d.
     */
    @Test
    void comp_globalMinT_bestT_declared() {
        assertTrue(compSource.contains("bestT"),
                "raycast_esvt.comp must declare and use 'bestT' for global min-t tracking — "
                + "pre-fix returns on first hit without comparing to previously found closer hits");
        assertTrue(compSource.contains("< bestT"),
                "raycast_esvt.comp must guard hit acceptance with '< bestT' to track the closest "
                + "hit across all DFS branches — pre-fix has no such guard");
    }

    /**
     * Pin 5: no first-hit early {@code return} inside the traversal while loop.
     *
     * <p>The pre-fix shader contained {@code return;} at two sites inside the child-scan loop
     * (LOD termination ~line 812 and leaf hit ~line 854). Post-fix converts both to
     * {@code continue} after updating best* variables, allowing the DFS to find closer hits in
     * subsequent children or sub-trees. The {@code return} after stack exhaustion (outside the
     * while loop) is permitted.
     *
     * <p>The pin checks structural absence of a first-hit pattern: the LOD block must
     * {@code continue} (not {@code return}) after updating best state, and the leaf hit block
     * likewise. We detect this by verifying no "contourRef.t" assignment is followed by an
     * immediate {@code return} — post-fix the contourRef path leads to a bestT update and
     * {@code continue}.
     *
     * <p>This test is RED before P3 Fix 1d.
     */
    @Test
    void comp_noFirstHitReturn_insideChildLoop() {
        // After post-fix both LOD and leaf hit sites do: bestT = contourRef.t; ... continue;
        // The absence of a bare "return;" following the contourRef block is the positive signal.
        // We check: if contourRef.t is assigned (bestT = contourRef.t), a return; immediately
        // following would be wrong. Post-fix uses continue instead.
        // Structural check: any "continue;" following a contourRef.t assignment is correct;
        // a "return;" following it is the old first-hit bug.
        //
        // Simple proxy: after fix, "contourRef.t" is used in bestT assignment lines and
        // NOT in the final-return-gate. The cleaner check is: the old unconditional
        // "return;" that immediately follows the normal-calculation block is gone.
        // Post-fix both normals blocks end in a "continue;" not "return;".
        assertFalse(compSource.contains("hitNormal = -hitNormal;\n                }\n\n                return;"),
                "raycast_esvt.comp must not contain the first-hit 'return;' immediately after "
                + "the hitNormal flip block — post-fix must 'continue' to find the global min-t hit");
    }

    // -------------------------------------------------------------------------
    // esvt_ray_traversal.cl pins (P3 Fix 2 + regression)
    // -------------------------------------------------------------------------

    /**
     * Pin 6: dead {@code CHILD_ORDER[96]} constant must be absent from the kernel.
     *
     * <p>The pre-fix kernel declared {@code constant int CHILD_ORDER[96] = {...};} at lines
     * 130-145. This constant was never referenced in the traversal loop (which uses the
     * correct all-8 Morton scan), making it dead code. Its presence is a semantic hazard:
     * it implies the kernel uses 4-child entry-face ordering. Removed in P3 Fix 2.
     *
     * <p>This test is RED before P3 Fix 2.
     */
    @Test
    void cl_childOrder96_absent() {
        assertFalse(clSource.contains("CHILD_ORDER[96]"),
                "esvt_ray_traversal.cl must not contain the dead 'CHILD_ORDER[96]' constant — "
                + "the kernel traversal does not use this table; its presence falsely implies "
                + "4-child entry-face ordering");
        assertFalse(clSource.contains("CHILD_ORDER"),
                "esvt_ray_traversal.cl must not reference 'CHILD_ORDER' at all — "
                + "the dead constant and any associated reference must be removed");
    }

    /**
     * Pin 7 (regression, revised by 2s3jc): all-8 Morton scan must be retained in the kernel
     * — now as the front-to-back COLLECT loop.
     *
     * <p>The jk5tk kernel visited all 8 children via
     * {@code for (int childIdx = siblingPos; childIdx < 8; childIdx++)}. 2s3jc restructured
     * this into COLLECT ({@code for (int childIdx = 0; childIdx < 8; childIdx++)}) + sorted
     * VISIT; the all-8 scan survives as the collection pass and must not be narrowed.
     */
    @Test
    void cl_allEightScan_retained() {
        assertTrue(clSource.contains("childIdx < 8"),
                "esvt_ray_traversal.cl must retain 'childIdx < 8' in the traversal loop — "
                + "this is the correct all-8 Morton scan; it must not be removed by P3 Fix 2");
    }

    /**
     * Pin 8 (regression): global min-t tracking must be retained in the kernel.
     *
     * <p>The correct {@code esvt_ray_traversal.cl} traversal uses {@code bestT} and
     * {@code < bestT} to accumulate the globally closest hit. This regression guard ensures
     * P3 Fix 2 (deletion of the dead CHILD_ORDER block) does not accidentally corrupt the
     * bestT tracking.
     *
     * <p>This test is GREEN before P3 Fix 2 and must stay GREEN after.
     */
    @Test
    void cl_globalMinT_retained() {
        assertTrue(clSource.contains("bestT"),
                "esvt_ray_traversal.cl must retain 'bestT' global min-t variable — "
                + "must not be removed by P3 Fix 2");
        assertTrue(clSource.contains("< bestT"),
                "esvt_ray_traversal.cl must retain '< bestT' guard for closest-hit selection — "
                + "must not be removed by P3 Fix 2");
    }

    // -------------------------------------------------------------------------
    // raycast_esvt.comp Morton→Bey conversion pin
    // -------------------------------------------------------------------------

    /**
     * Pin 9: Morton→Bey conversion must precede getChildVertices and PARENT_TYPE_TO_CHILD_TYPE.
     *
     * <p>ESVT stores children in Morton order (childmask/leafmask bit N = Morton slot N), but
     * the Bey subdivision geometry assigns vertices by Bey child ID. {@code getChildVertices}'
     * switch cases 0-7 are Bey-indexed; {@code PARENT_TYPE_TO_CHILD_TYPE[type][n]} is also
     * Bey-indexed. Passing a raw Morton {@code childIdx} to either produces wrong geometry and
     * wrong child types at every non-identity slot (type 0: Morton 2 → Bey 4, so Morton slot 2
     * maps to the octahedral piece, not the v2-corner piece as case 2 specifies).
     *
     * <p>Post-fix: {@code INDEX_TO_BEY_NUMBER[6][8]} constant added; the traversal loop computes
     * {@code int beyIdx = INDEX_TO_BEY_NUMBER[parentType][childIdx];} once per candidate child
     * and passes {@code beyIdx} to {@code getChildVertices} and {@code PARENT_TYPE_TO_CHILD_TYPE}.
     * The Morton index is retained for bitset operations ({@code hasChild}, {@code isChildLeaf},
     * {@code getChildIndex}) which read the Morton-ordered childmask/leafmask directly.
     *
     * <p>This test is RED before the INDEX_TO_BEY_NUMBER fix (Luciferase-g51tc review round 2).
     */
    @Test
    void comp_mortonToBeyConversion_present() {
        assertTrue(compSource.contains("INDEX_TO_BEY_NUMBER"),
                "raycast_esvt.comp must declare and use 'INDEX_TO_BEY_NUMBER' for Morton→Bey conversion — "
                + "getChildVertices and PARENT_TYPE_TO_CHILD_TYPE are Bey-indexed but the traversal loop "
                + "iterates Morton childIdx; without conversion, non-identity Morton→Bey slots produce wrong "
                + "geometry and wrong child types (e.g. type 0 Morton 2 → Bey 4, but case 2 gives the v2 corner)");
    }

    /**
     * Pin 5b (robustness, revised by 2s3jc): leaf-hit and LOD-hit sites must continue with the
     * stable marker comment, not return — at all FOUR sites.
     *
     * <p>Pin 5 used a whitespace-exact negative assertion that could miss a reintroduced
     * {@code return} with different surrounding whitespace. The hit sites explicitly annotate
     * the continuation with {@code // Keep scanning for closer hits} — a stable marker present
     * if and only if the correct {@code continue} replaces the old first-hit {@code return}.
     *
     * <p>2s3jc duplicated the LOD-termination and leaf-hit bodies across the fresh (collect+sort)
     * and resume visit paths, so the marker count doubled from 2 to 4: fresh-LOD, fresh-leaf,
     * resume-LOD, resume-leaf.
     */
    @Test
    void comp_hitSites_continueWithMarker() {
        var marker = "continue; // Keep scanning for closer hits";
        int count = 0;
        int idx = compSource.indexOf(marker);
        while (idx >= 0) {
            count++;
            idx = compSource.indexOf(marker, idx + 1);
        }
        assertEquals(4, count,
                "raycast_esvt.comp must carry 'continue; // Keep scanning for closer hits' at all FOUR "
                + "hit sites — LOD + leaf in BOTH the fresh and resume visit paths (found " + count + ") "
                + "— a bare return; at any site is the pre-fix first-hit-only bug");
    }

    // -------------------------------------------------------------------------
    // esvt_ray_traversal.cl output blend pins (P4 fixes)
    // -------------------------------------------------------------------------

    /**
     * Pin 10: root-without-leaf path must be a MISS — hitNormals w component must not be set to 1.
     *
     * <p>The pre-fix kernel set {@code rootOnlyNormal.w = 1.0f} in the no-leaf-but-root-cube-hit
     * path. The parity test reads {@code hitNormals.w > 0.5} as a hit flag, so every ray entering
     * the [0,1]³ root cube was counted as a hit regardless of tree content — a tree-independent
     * silhouette mask (3301/4096 hits for sphere and 6-node tree alike, byte-identical parity
     * numbers across P1–P3 revisions). Post-fix: the root-without-leaf branch writes
     * {@code w=0} (no hit) matching the root-miss branch, gated by a stable marker comment
     * {@code // root-without-leaf is a MISS}.
     *
     * <p>This test is RED before the P4 output-blend fix.
     */
    @Test
    void cl_rootWithoutLeaf_isMiss() {
        assertTrue(clSource.contains("// root-without-leaf is a MISS"),
                "esvt_ray_traversal.cl root-only output path must contain the marker comment "
                + "'// root-without-leaf is a MISS' — pre-fix set hitNormals.w=1.0f there, "
                + "flagging every root-cube-entering ray as a hit regardless of tree content");
        // Also check the debug rootOnlyNormal w=1.0f literal is gone
        assertFalse(clSource.contains("rootOnlyNormal"),
                "esvt_ray_traversal.cl must not contain the debug 'rootOnlyNormal' variable — "
                + "the arithmetic blend with w=1.0f is the parity-killing defect");
    }

    /**
     * Pin 11: leaf normal output must reference bestHitNormal, not a debug depth gradient.
     *
     * <p>The pre-fix kernel discarded {@code bestHitNormal} and output a depth-based color
     * gradient {@code (1-normalizedDepth, normalizedDepth, 0.3, 1.0)} as the leaf normal.
     * This produces wrong normals for all leaf hits. Post-fix: the leaf output path references
     * {@code bestHitNormal.x}, {@code bestHitNormal.y}, {@code bestHitNormal.z} directly.
     *
     * <p>This test is RED before the P4 output-blend fix.
     */
    @Test
    void cl_leafNormal_usesBestHitNormal() {
        assertTrue(clSource.contains("bestHitNormal.x"),
                "esvt_ray_traversal.cl leaf normal output must reference 'bestHitNormal.x' — "
                + "pre-fix discarded bestHitNormal and output a debug depth-gradient instead");
        assertFalse(clSource.contains("normalizedDepth"),
                "esvt_ray_traversal.cl must not contain the debug 'normalizedDepth' depth-gradient "
                + "computation — the real bestHitNormal must be written to hitNormals instead");
        assertFalse(clSource.contains("leafNormal"),
                "esvt_ray_traversal.cl must not contain the debug 'leafNormal' variable — "
                + "post-fix writes bestHitNormal directly to hitNormals[gid]");
    }

    // -------------------------------------------------------------------------
    // 2s3jc front-to-back pins (Luciferase-y3l06)
    // -------------------------------------------------------------------------

    /** The exact tandem-sort comparator shared by ESVTTraversal.java, .cl, and .comp. */
    private static final String COMPARATOR =
        "candidateTEntry[j] == ti && candidateIdx[j] > ci";

    /** The exact prune-break marker shared by ESVTTraversal.java, .cl, and .comp. */
    private static final String PRUNE_MARKER =
        "break; // prune: next tEntry >= bestT, break";

    /**
     * Pin 12: tandem insertion sort must be present in the OpenCL kernel with the exact
     * CPU comparator.
     *
     * <p>2s3jc front-to-back requires all three implementations (Java, .cl, .comp) to sort
     * candidates with the identical comparator — ascending tEntry, childIdx-ascending tiebreak.
     * Any divergence in the comparator is the jk5tk three-way-divergence bug class: results
     * stay correct for most scenes but visit order (and therefore tie resolution and pruning)
     * silently differs across implementations.
     */
    @Test
    void cl_insertionSort_present() {
        assertTrue(clSource.contains("INSERTION SORT ascending by tEntry, tiebreak childIdx ascending"),
                "esvt_ray_traversal.cl must carry the front-to-back insertion-sort block — "
                + "without it the kernel visits children in Morton order and cannot prune");
        assertTrue(clSource.contains(COMPARATOR),
                "esvt_ray_traversal.cl insertion sort must use the exact CPU comparator '"
                + COMPARATOR + "' — any deviation diverges visit order from ESVTTraversal.java");
    }

    /**
     * Pin 13: tandem insertion sort must be present in the compute shader with the exact
     * CPU comparator. Same contract as Pin 12 for raycast_esvt.comp.
     */
    @Test
    void comp_insertionSort_present() {
        assertTrue(compSource.contains("INSERTION SORT ascending by tEntry, tiebreak childIdx ascending"),
                "raycast_esvt.comp must carry the front-to-back insertion-sort block — "
                + "without it the shader visits children in Morton order and cannot prune");
        assertTrue(compSource.contains(COMPARATOR),
                "raycast_esvt.comp insertion sort must use the exact CPU comparator '"
                + COMPARATOR + "' — any deviation diverges visit order from ESVTTraversal.java");
    }

    /**
     * Pin 14: the .cl StackEntry must carry the packed sortedOrder field.
     *
     * <p>The OpenCL stack is private per-work-item, so a dedicated struct field is the
     * correct storage for the packed sorted order (count in bits 27-24, eight 3-bit Morton
     * childIdx slots at bits 23-0). The resume path reads it via
     * {@code stack[scale].sortedOrder}; without it, resume cannot reconstruct the sorted
     * visit order and would fall back to Morton-order scanning.
     */
    @Test
    void cl_sortedOrder_stackField() {
        assertTrue(clSource.contains("int sortedOrder;"),
                "esvt_ray_traversal.cl StackEntry must declare 'int sortedOrder;' — the packed "
                + "sorted visit order the resume path replays after pop");
        assertTrue(clSource.contains("stack[scale].sortedOrder"),
                "esvt_ray_traversal.cl must read/write 'stack[scale].sortedOrder' on push and resume");
    }

    /**
     * Pin 15: the .comp packed sorted-order slot must pack the resume cursor into the high
     * nibble of the EXISTING stack_siblingPos slot.
     *
     * <p>Layout: bits 31-28 = sortedPos resume cursor (0-8), bits 27-24 = count, bits 23-0 =
     * sorted childIdx slots. Exactly 32 bits — written with uint casts to avoid sign-extension
     * on the high nibble. The write {@code int((uint(si + 1) << 28) | uint(packedOrder))} and
     * the cursor read {@code >> 28) & 0xFu} are both required.
     */
    @Test
    void comp_sortedOrder_packed() {
        assertTrue(compSource.contains("int((uint(si + 1) << 28) | uint(packedOrder))"),
                "raycast_esvt.comp push must pack the resume cursor (si+1) into bits 31-28 of the "
                + "stack_siblingPos slot alongside the 28-bit packedOrder");
        assertTrue(compSource.contains(">> 28) & 0xFu"),
                "raycast_esvt.comp pop must extract the resume cursor from the high nibble via "
                + "unsigned shift — signed shift would sign-extend when sortedPos == 8");
    }

    /**
     * Pin 16: every .comp shared-stack access must use the per-thread offset.
     *
     * <p>The shared arrays are indexed {@code stackBase + scale} with
     * {@code stackBase = threadIdx * CAST_STACK_DEPTH} — the 0bn1q race class: any access
     * missing the per-thread base reads/writes another invocation's stack slice.
     */
    @Test
    void comp_sortedOrder_perThreadOffset() {
        assertTrue(compSource.contains("int stackBase = threadIdx * CAST_STACK_DEPTH;"),
                "raycast_esvt.comp must compute the per-thread stack base — shared arrays are "
                + "sliced per invocation (0bn1q race class)");
        assertTrue(compSource.contains("stack_siblingPos[stackBase + scale]"),
                "raycast_esvt.comp must index the packed sorted-order slot with 'stackBase + scale' — "
                + "a bare [scale] access races against other invocations in the workgroup");
        assertFalse(compSource.contains("stack_siblingPos[scale]"),
                "raycast_esvt.comp must not index stack_siblingPos without the per-thread base");
    }

    /**
     * Pin 17: the .comp shared-memory stack must remain exactly 5 arrays — NO 6th shared array.
     *
     * <p>The workgroup shared budget is 32 KiB; the 5 arrays (64 threads x CAST_STACK_DEPTH x
     * 4 bytes each) total 28,160 bytes. Adding a 6th array (e.g. a dedicated sortedOrder array,
     * +5,632 bytes = 33,792) exceeds the limit on common hardware. This is why the sorted order
     * is packed into the existing stack_siblingPos slot rather than stored separately.
     */
    @Test
    void comp_noSixthSharedArray() {
        // Count declarations only (line-start "shared "), not prose mentions in comments
        long count = compSource.lines().filter(l -> l.startsWith("shared ")).count();
        assertEquals(5, count,
                "raycast_esvt.comp must declare exactly 5 shared stack arrays (found " + count + ") — "
                + "a 6th would push workgroup shared memory past the 32 KiB budget "
                + "(28,160 B -> 33,792 B); pack new per-level state into existing slots instead");
    }

    /**
     * Pin 18: the prune-break marker must be present in the OpenCL kernel — in BOTH the fresh
     * and resume visit paths.
     *
     * <p>The prune is the entire point of front-to-back: once the next sorted child's tEntry
     * is >= bestT, no remaining child can improve the result and the visit loop BREAKs.
     * Replacing the break with continue (or removing the check) silently reverts to exhaustive
     * scanning — results stay identical, performance regresses, and no result-pin catches it.
     */
    @Test
    void cl_pruneBreak_marker() {
        int count = 0;
        int idx = clSource.indexOf(PRUNE_MARKER);
        while (idx >= 0) {
            count++;
            idx = clSource.indexOf(PRUNE_MARKER, idx + 1);
        }
        assertEquals(2, count,
                "esvt_ray_traversal.cl must carry '" + PRUNE_MARKER + "' in BOTH the fresh and "
                + "resume visit paths (found " + count + ") — removing either silently reverts "
                + "that path to exhaustive scanning");
    }

    /**
     * Pin 19: the prune-break marker must be present in the compute shader — in BOTH the fresh
     * and resume visit paths. Same contract as Pin 18 for raycast_esvt.comp.
     */
    @Test
    void comp_pruneBreak_marker() {
        int count = 0;
        int idx = compSource.indexOf(PRUNE_MARKER);
        while (idx >= 0) {
            count++;
            idx = compSource.indexOf(PRUNE_MARKER, idx + 1);
        }
        assertEquals(2, count,
                "raycast_esvt.comp must carry '" + PRUNE_MARKER + "' in BOTH the fresh and "
                + "resume visit paths (found " + count + ") — removing either silently reverts "
                + "that path to exhaustive scanning");
    }
}
