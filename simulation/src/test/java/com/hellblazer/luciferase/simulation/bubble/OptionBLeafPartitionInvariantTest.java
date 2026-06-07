/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-018 AC-1 (Luciferase-0sxck): validates Option B's design against the codebase.
 * <p>
 * Option B replaces RDR-015's flat single-level tiling invariant with a <b>leaf partition of a tetree
 * refinement forest</b>. This test pins that invariant directly, plus the up-walk router's watermark
 * behaviour, in both the uniform (base-level) and mixed-level (refined / collapsed) states — using only
 * the bubble grid (no topology-package driver), so it validates the grid contract the migration router
 * depends on independently of split/merge plumbing.
 *
 * <h2>Leaf-partition invariant (RDR-018 ## Decision, "Invariant restatement")</h2>
 * <ol>
 *   <li><b>Coverage:</b> every point in the OPEN INTERIOR of {@link WorldBounds} is contained in at least
 *       one registered leaf bubble. {@code createBubbles} BFS-includes every Tet cell whose AABB overlaps
 *       the world box; Kuhn/Bey tets tile space exactly (verified by {@code T8codeDtetOracleTest}), so the
 *       single tiling cell containing an interior point is always registered. A Bey split replaces a parent
 *       leaf with its 8 children, which tile the parent exactly — coverage is preserved inductively.</li>
 *   <li><b>No interior overlap:</b> in a hierarchical simplex refinement any two cells are either nested
 *       (ancestor/descendant) or have disjoint interiors — there is no partial overlap. A split removes the
 *       parent (AC-2.5) and a collapse removes the children (q37mx), so the registered leaf set is an
 *       antichain (no leaf is an ancestor of another) ⇒ pairwise-disjoint interiors ⇒ each interior point is
 *       owned by EXACTLY one leaf.</li>
 *   <li><b>Boundary precision (gate S1):</b> the "exactly one" claim is over OPEN INTERIORS only. A point on
 *       a 2D tet face can satisfy {@code contains12DOP} for more than one leaf (non-conforming Bey-SFC faces
 *       share 0–3 vertices); the {@code contains12DOP} closed-simplex strict-ordering tie-break resolves such
 *       points deterministically to a single leaf. This test samples strictly interior points (jittered off
 *       the integer lattice) so the count is exactly 1.</li>
 *   <li><b>Leaf level ≥ base:</b> every leaf is at level {@code >= getBaseLevel()} (the uniform initial level
 *       is the depth-0 base case; refinement only deepens).</li>
 * </ol>
 *
 * <h2>Up-walk routing + watermark (RDR-018 ## Decision, "Routing algorithm")</h2>
 * Termination: {@code resolveLeafKey} locates at {@code maxLeafLevel} then walks {@code tet.parent()}; each
 * step strictly decreases the level (monotone toward L0), terminating at the deepest existing leaf or, below
 * the base level with no hit, at {@code null} — finite in ≤ {@code (maxLeafLevel - base + 1)} steps.
 * <p>
 * <b>Watermark contract — design→implementation refinement (recorded explicitly, AC-7 discipline):</b> the
 * RDR Design wrote "raise on split, recompute or lower on merge". The IMPLEMENTED contract is
 * <b>monotonic-up</b> — the watermark is never lowered, even after a collapse. This is correct, not a
 * regression: {@code removeBubble} purges the collapsed child keys, so locating one level too deep at a
 * former-refined region finds no L+1 leaf and the up-walk simply climbs to the re-registered parent. The
 * "or lower" clause was an optional optimisation (one fewer up-walk step), not a correctness requirement;
 * the simpler monotonic-up choice is proven here and by {@code BubbleMergerCollapseTest} /
 * {@code SplitMergeMigrationRegressionTest}.
 *
 * <h2>Capacity model (RQ-2), stated</h2>
 * Per-leaf density cap. A leaf triggers a split when it exceeds the {@code BubbleSplitter} capacity threshold
 * (documented at &gt;5000 entities/leaf). Total world capacity = (number of leaves) × per-leaf-cap. A Bey
 * split refines one over-capacity leaf into 8 children (net +7 leaves), multiplying that region's LOCAL
 * capacity ~8× while leaving the rest of the partition untouched — capacity is added <em>spatially and
 * locally</em> exactly where it is needed (the central reason Option B is chosen over Option A's global cap).
 * A collapse is the inverse (−7 leaves) once a refined region falls back below density. Refinement
 * granularity (all-8 uniform vs density-driven partial) is RQ-6, deferred to Luciferase-xtyki; this AC states
 * the model and validates the all-8 coverage geometry it relies on.
 *
 * @author hal.hildebrand
 */
class OptionBLeafPartitionInvariantTest {

    private static final WorldBounds WORLD = new WorldBounds(0.0f, 100.0f);

    private static TetreeBubbleGrid partitionGrid() {
        var grid = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(8, WORLD, 10L);
        return grid;
    }

    /** Centroid of a tet (average of its 4 vertices) — strictly interior, never on a face. */
    private static Point3f centroid(Tet tet) {
        var v = tet.coordinates();
        return new Point3f((v[0].x + v[1].x + v[2].x + v[3].x) / 4f,
                           (v[0].y + v[1].y + v[2].y + v[3].y) / 4f,
                           (v[0].z + v[1].z + v[2].z + v[3].z) / 4f);
    }

    /** Number of REGISTERED leaf bubbles whose tetrahedron contains {@code p} by the exact 12-DOP test. */
    private static int containingLeafCount(TetreeBubbleGrid grid, Point3f p) {
        int count = 0;
        for (var key : grid.getBubblesWithKeys().keySet()) {
            if (Tet.tetrahedron(key).contains12DOP(p.x, p.y, p.z)) {
                count++;
            }
        }
        return count;
    }

    /** First registered leaf key containing {@code p} (callers assert count==1 first ⇒ it is the unique owner). */
    private static TetreeKey<?> theContainingLeaf(TetreeBubbleGrid grid, Point3f p) {
        for (var key : grid.getBubblesWithKeys().keySet()) {
            if (Tet.tetrahedron(key).contains12DOP(p.x, p.y, p.z)) {
                return key;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // 1. Uniform base partition: every leaf centroid is owned by exactly one base-level leaf,
    //    and resolveLeafKey agrees.
    // -----------------------------------------------------------------------

    @Test
    void uniformPartition_everyLeafCentroidOwnedByExactlyOneBaseLeaf() {
        var grid   = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base  = grid.getBaseLevel();
        assertTrue(base > 0, "must be a single-level partition");
        assertEquals(base, grid.getMaxLeafLevel(), "watermark starts at the base level (no refinement yet)");

        for (var key : grid.getBubblesWithKeys().keySet()) {
            assertEquals(base, key.getLevel(), "every leaf must be at the base level in a uniform partition");
            var here = centroid(Tet.tetrahedron(key));
            assertEquals(1, containingLeafCount(grid, here),
                         "leaf centroid must be contained by EXACTLY one registered leaf (no overlap)");
            assertEquals(key, grid.resolveLeafKey(tetree, here),
                         "resolveLeafKey must return the owning leaf for its own centroid");
        }
    }

    // -----------------------------------------------------------------------
    // 2. Coverage + no-overlap over a dense interior sample (the partition is a true tiling).
    // -----------------------------------------------------------------------

    @Test
    void uniformPartition_denseInteriorSampleCoveredByExactlyOneLeaf() {
        var grid   = partitionGrid();
        var tetree = grid.getSpatialIndex();

        // Strictly-interior barycentric weight sets (all components > 0 ⇒ never on a face/edge/vertex). A
        // uniform-lattice sample is unusable here: points like (k,k,k) lie on the cube main diagonal — the
        // shared edge of all 6 Kuhn tets — which is precisely the boundary the open-interior invariant
        // excludes. Per-leaf barycentric points are guaranteed interior to their own leaf and to no other.
        float[][] weights = {
            {0.25f, 0.25f, 0.25f, 0.25f},  // centroid
            {0.70f, 0.10f, 0.10f, 0.10f},  // biased toward each vertex (still strictly interior)
            {0.10f, 0.70f, 0.10f, 0.10f},
            {0.10f, 0.10f, 0.70f, 0.10f},
            {0.10f, 0.10f, 0.10f, 0.70f},
            {0.40f, 0.30f, 0.20f, 0.10f},  // an off-symmetric interior point
        };

        int interiorSamples = 0;
        for (var key : grid.getBubblesWithKeys().keySet()) {
            var tet = Tet.tetrahedron(key);
            for (var w : weights) {
                var p = baryPoint(tet, w);
                int count = containingLeafCount(grid, p);
                // Coverage + no-overlap: every strictly-interior sample is in EXACTLY one registered leaf.
                assertEquals(1, count, "interior point " + p + " must be covered by exactly one leaf");
                assertEquals(key, theContainingLeaf(grid, p),
                             "the unique owner must be the leaf the point was sampled from");
                assertEquals(key, grid.resolveLeafKey(tetree, p),
                             "resolveLeafKey must agree with the unique geometric owner at " + p);
                interiorSamples++;
            }
        }
        // Non-vacuous bound tied to the actual partition size (every leaf × every weight set must run).
        assertEquals(6 * grid.getBubblesWithKeys().size(), interiorSamples,
                     "every registered leaf must be sampled by every weight set");
    }

    /** A strictly-interior point of {@code tet} from positive barycentric weights (sum == 1). */
    private static Point3f baryPoint(Tet tet, float[] w) {
        var v = tet.coordinates();
        return new Point3f(w[0] * v[0].x + w[1] * v[1].x + w[2] * v[2].x + w[3] * v[3].x,
                           w[0] * v[0].y + w[1] * v[1].y + w[2] * v[2].y + w[3] * v[3].y,
                           w[0] * v[0].z + w[1] * v[1].z + w[2] * v[2].z + w[3] * v[3].z);
    }

    // -----------------------------------------------------------------------
    // 3. Mixed-level forest (one cell refined into its 8 Bey children): invariant still holds —
    //    refined region owned by exactly one L+1 child, unrefined regions still by their base leaf.
    // -----------------------------------------------------------------------

    @Test
    void mixedLevelForest_invariantHolds_refinedRegionOwnedByExactlyOneChild() {
        var grid   = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base  = grid.getBaseLevel();

        var parentKey = grid.getBubblesWithKeys().keySet().iterator().next();
        var parentTet = Tet.tetrahedron(parentKey);
        var children  = parentTet.geometricSubdivide();

        // Refine: remove the parent leaf, add its 8 children (the leaf set stays an antichain).
        grid.removeBubble(grid.getBubble(parentKey).id());
        TetreeKey<?>[] childKeys = new TetreeKey[8];
        for (int i = 0; i < 8; i++) {
            childKeys[i] = children[i].tmIndex();
            grid.addBubble(new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L), childKeys[i]);
        }
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(), "watermark raised to base+1 by refinement");

        // Every leaf level >= base; the antichain has no parent/child both present.
        assertFalse(grid.containsBubble(parentKey), "refined parent must NOT remain registered (no overlap)");
        for (var key : grid.getBubblesWithKeys().keySet()) {
            assertTrue(key.getLevel() >= base, "every leaf must be at level >= base");
        }

        // Refined region: each child centroid is owned by EXACTLY one registered leaf (that child).
        // A Bey-child centroid is strictly interior to the child, hence interior to the parent's former
        // territory, which no OTHER registered leaf can contain (the parent was removed, siblings tile the
        // parent with disjoint interiors) — so the count is exactly one, not a face-ambiguity case.
        for (int i = 0; i < 8; i++) {
            var here = centroid(children[i]);
            assertEquals(1, containingLeafCount(grid, here),
                         "refined-region interior point must be owned by exactly one leaf (the child)");
            assertEquals(childKeys[i], grid.resolveLeafKey(tetree, here),
                         "refined-region point must resolve to its deep child leaf (deepest-leaf up-walk)");
            assertEquals((byte) (base + 1), grid.resolveLeafKey(tetree, here).getLevel(),
                         "resolved leaf must be at the deeper level");
        }

        // Unrefined regions: still owned by their base leaf, count exactly one.
        for (var key : grid.getBubblesWithKeys().keySet()) {
            if (key.getLevel() != base) {
                continue;
            }
            var here = centroid(Tet.tetrahedron(key));
            assertEquals(1, containingLeafCount(grid, here),
                         "unrefined-region interior point must still be owned by exactly one base leaf");
            assertEquals(key, grid.resolveLeafKey(tetree, here),
                         "unrefined region must still resolve to its base-level cell in a mixed-level grid");
        }
    }

    // -----------------------------------------------------------------------
    // 4. Watermark is monotonic-up: after a collapse it STAYS at base+1, and the up-walk still resolves
    //    the collapsed region to the re-registered parent (the implemented contract; see class javadoc).
    // -----------------------------------------------------------------------

    @Test
    void watermarkMonotonicUp_afterCollapse_upWalkStillResolvesToParent() {
        var grid   = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base  = grid.getBaseLevel();

        var parentKey = grid.getBubblesWithKeys().keySet().iterator().next();
        var parentTet = Tet.tetrahedron(parentKey);
        var children  = parentTet.geometricSubdivide();

        // Refine, then collapse by hand (remove children, re-register parent) — no topology driver needed.
        grid.removeBubble(grid.getBubble(parentKey).id());
        for (int i = 0; i < 8; i++) {
            grid.addBubble(new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L), children[i].tmIndex());
        }
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(), "watermark raised by refinement");

        for (int i = 0; i < 8; i++) {
            grid.removeBubble(grid.getBubble(children[i].tmIndex()).id());
        }
        grid.addBubble(new EnhancedBubble(UUID.randomUUID(), base, 10L), parentKey);

        // Monotonic-up: the watermark is NOT lowered back to base after the collapse.
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(),
                     "watermark must stay at base+1 after collapse (monotonic-up, never lowered)");

        // Despite locating one level too deep, the up-walk climbs to the re-registered parent because the
        // child keys were purged: locate at base+1 → child key absent → walk to parent.
        for (int i = 0; i < 8; i++) {
            var here = centroid(children[i]);
            assertEquals(1, containingLeafCount(grid, here),
                         "post-collapse interior point must be owned by exactly one leaf (the parent)");
            assertEquals(parentKey, grid.resolveLeafKey(tetree, here),
                         "post-collapse up-walk must resolve the region to the re-registered parent");
        }
    }

    // -----------------------------------------------------------------------
    // 5. Boundary precision (gate S1): a point ON a tet boundary (the cube main diagonal, shared edge of
    //    the 6 Kuhn tets) IS accepted by >1 leaf via contains12DOP — exactly the open-interior exclusion.
    //    The router's tie-break resolves it deterministically to a single registered leaf (not null, not
    //    ambiguous). This validates the named contains12DOP tie-break component of the invariant.
    // -----------------------------------------------------------------------

    @Test
    void faceBoundaryPoint_resolvedDeterministicallyToASingleLeaf_tieBreak() {
        var grid   = partitionGrid();
        var tetree = grid.getSpatialIndex();
        byte base  = grid.getBaseLevel();
        float h     = (float) Constants.lengthAtLevel(base);

        // The cube centre of a base cell lies on that cube's main diagonal — the shared edge of all 6 Kuhn
        // tets of the cube — so multiple registered leaves' contains12DOP accept it (a true boundary point).
        Point3f boundary = null;
        int     accepts  = 0;
        for (var key : grid.getBubblesWithKeys().keySet()) {
            var v0 = Tet.tetrahedron(key).coordinates()[0]; // Kuhn anchor (cube origin)
            var c  = new Point3f(v0.x + h / 2f, v0.y + h / 2f, v0.z + h / 2f);
            if (c.x <= 0f || c.y <= 0f || c.z <= 0f || c.x >= 100f || c.y >= 100f || c.z >= 100f) {
                continue;
            }
            int n = containingLeafCount(grid, c);
            if (n >= 2) {        // genuinely in the tie-break regime (accepted by multiple leaves)
                boundary = c;
                accepts  = n;
                break;
            }
        }
        assertNotNull(boundary, "must find a boundary point accepted by >= 2 leaves (the tie-break regime)");
        assertTrue(accepts >= 2, "boundary point must be accepted by multiple leaves; was " + accepts);

        // The tie-break resolves it to exactly one registered leaf, deterministically, and that leaf really
        // does contain the point — "deterministically to a single leaf" (RDR-018 gate S1).
        var r1 = grid.resolveLeafKey(tetree, boundary);
        var r2 = grid.resolveLeafKey(tetree, boundary);
        assertNotNull(r1, "tie-break must resolve a boundary point to a single leaf, never null");
        assertEquals(r1, r2, "boundary-point resolution must be deterministic");
        assertTrue(grid.containsBubble(r1), "resolved leaf must be a registered bubble");
        assertTrue(Tet.tetrahedron(r1).contains12DOP(boundary.x, boundary.y, boundary.z),
                   "the resolved leaf must be one whose closed simplex contains the boundary point");
    }

    // -----------------------------------------------------------------------
    // 6. Coverage MEASURED (not inferred) over WorldBounds: a fixed-seed cloud of in-bounds points is each
    //    covered by >= 1 registered leaf and resolves to a real leaf — detects a BFS gap that per-leaf
    //    sampling cannot (a gap point belongs to no leaf's barycentric set). S2.
    // -----------------------------------------------------------------------

    @Test
    void coverageMeasuredOverWorldBounds_noGapInTheBfsTiling() {
        var grid   = partitionGrid();
        var tetree = grid.getSpatialIndex();
        var rng    = new java.util.Random(20260607L); // fixed seed → deterministic

        int probes = 0;
        for (int i = 0; i < 400; i++) {
            // Strictly inside the open WorldBounds interior (0,100); margin keeps off the world walls.
            float x = 0.5f + rng.nextFloat() * 99f;
            float y = 0.5f + rng.nextFloat() * 99f;
            float z = 0.5f + rng.nextFloat() * 99f;
            var p = new Point3f(x, y, z);
            // Coverage: every in-bounds point is owned by AT LEAST one leaf (no gap). A point that happens
            // to land on a shared boundary may be accepted by >1 leaf — that is fine for a coverage check;
            // no-overlap is pinned separately by the strictly-interior barycentric test.
            assertTrue(containingLeafCount(grid, p) >= 1, "WorldBounds interior point " + p + " is uncovered (BFS gap)");
            assertNotNull(grid.resolveLeafKey(tetree, p), "router must resolve every in-bounds point to a real leaf");
            probes++;
        }
        assertEquals(400, probes, "all coverage probes must run");
    }

    // -----------------------------------------------------------------------
    // 7. Termination / out-of-bounds: a point outside WorldBounds has no owning leaf → null
    //    (never an L0 catch-all — R1). The up-walk terminates at the base level.
    // -----------------------------------------------------------------------

    @Test
    void outOfWorldBounds_resolvesToNull_walkTerminatesAtBase() {
        var grid   = partitionGrid();
        var tetree = grid.getSpatialIndex();
        var outside = new Point3f(50_000f, 50_000f, 50_000f);
        assertNull(grid.resolveLeafKey(tetree, outside),
                   "out-of-bounds position must resolve to null (walk terminates at base, no L0 catch-all)");
        assertEquals(0, containingLeafCount(grid, outside),
                     "no registered leaf may contain an out-of-bounds point");
    }
}
