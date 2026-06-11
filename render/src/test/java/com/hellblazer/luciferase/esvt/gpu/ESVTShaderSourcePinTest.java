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
 * Source-pin tests for ESVT GPU shader and kernel correctness (P3 — Luciferase-g51tc).
 *
 * <p>Eight headless tests pin the post-fix textual contracts for:
 * <ol>
 *   <li>{@code raycast_esvt.comp} — all-8 Morton loop: uses {@code childIdx < 8}, NOT the old {@code pos < 4}
 *       CHILD_ORDER positional loop (P3 Fix 1a)</li>
 *   <li>{@code raycast_esvt.comp} — CHILD_ORDER constant absent: the dead 4-child table has been removed
 *       (P3 Fix 1b)</li>
 *   <li>{@code raycast_esvt.comp} — siblingPos shared stack present: {@code stack_siblingPos} array added for
 *       correct resume-after-pop (P3 Fix 1c)</li>
 *   <li>{@code raycast_esvt.comp} — global min-t tracking: {@code bestT} variable declared, LOD and leaf hit
 *       sites update {@code bestT} and {@code continue} rather than returning immediately (P3 Fix 1d)</li>
 *   <li>{@code raycast_esvt.comp} — no first-hit early return inside the child loop: the pre-fix pattern
 *       {@code return;} inside the traversal while loop is gone (P3 Fix 1d)</li>
 *   <li>{@code esvt_ray_traversal.cl} — dead CHILD_ORDER[96] block absent: the unused constant was removed
 *       (P3 Fix 2)</li>
 *   <li>{@code esvt_ray_traversal.cl} — all-8 scan retained: {@code childIdx < 8} still present in kernel
 *       (regression guard)</li>
 *   <li>{@code esvt_ray_traversal.cl} — global min-t retained: {@code bestT} and {@code < bestT} still
 *       present in kernel (regression guard)</li>
 * </ol>
 *
 * <p>All tests are headless (no GPU/OpenCL required); they pin the source text
 * loaded via the same {@link ShaderResourceLoader} / resource path used at runtime.
 * Tests 1–5 pin the compute shader; tests 6–8 pin the OpenCL kernel.
 *
 * <p>Bead: Luciferase-g51tc (P3 RED phase, TDD). Branch: feature/jk5tk-esvt-parity.
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
     * Pin 1: traversal loop must iterate all 8 Morton children ({@code childIdx < 8}).
     *
     * <p>The pre-fix shader iterated only 4 entry-face children via
     * {@code for (int pos = siblingPos; pos < 4; pos++)} with an indirection through
     * {@code CHILD_ORDER[parentType][entryFace][pos]}. This misses up to 4 children whose
     * tetrahedral geometry does intersect the ray but are not in the entry-face set.
     * Post-fix: {@code for (int childIdx = siblingPos; childIdx < 8; childIdx++)} in
     * Morton order, matching the {@code esvt_ray_traversal.cl} pattern.
     *
     * <p>This test is RED before P3 Fix 1a.
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
     * Pin 3: shared siblingPos stack must be present.
     *
     * <p>The pre-fix shader had {@code stack_nodes}, {@code stack_tmax}, {@code stack_type},
     * and {@code stack_entryFace} but no {@code stack_siblingPos}. On pop, it always reset
     * {@code siblingPos = 0}, restarting the child scan from Morton child 0 instead of resuming
     * after the child that triggered the descent. Post-fix adds:
     * {@code shared int stack_siblingPos[64 * CAST_STACK_DEPTH];} and saves
     * {@code childIdx + 1} on push, restores on pop.
     *
     * <p>This test is RED before P3 Fix 1c.
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
     * Pin 7 (regression): all-8 Morton scan must be retained in the kernel.
     *
     * <p>The correct {@code esvt_ray_traversal.cl} traversal already uses
     * {@code for (int childIdx = siblingPos; childIdx < 8; childIdx++)} in Morton order.
     * This regression guard ensures P3 Fix 2 (deletion of the dead CHILD_ORDER block)
     * does not accidentally remove or corrupt the traversal loop.
     *
     * <p>This test is GREEN before P3 Fix 2 and must stay GREEN after.
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
     * Pin 5b (robustness): leaf-hit and LOD-hit sites must continue with stable marker comment,
     * not return.
     *
     * <p>Pin 5 used a whitespace-exact negative assertion that could miss a reintroduced
     * {@code return} with different surrounding whitespace. Post-fix: both hit sites explicitly
     * annotate the continuation with {@code // keep scanning for closer hits} — a stable
     * marker that is present if and only if the correct {@code continue} replaces the old
     * {@code return}. This positive assertion is formatting-independent.
     *
     * <p>This test is GREEN after P3 Fix 1d and must stay GREEN through all subsequent edits.
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
        assertEquals(2, count,
                "raycast_esvt.comp must carry 'continue; // Keep scanning for closer hits' at BOTH "
                + "the LOD-termination and leaf hit sites (found " + count + ") "
                + "— a bare return; at either site is the pre-fix first-hit-only bug");
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
}
