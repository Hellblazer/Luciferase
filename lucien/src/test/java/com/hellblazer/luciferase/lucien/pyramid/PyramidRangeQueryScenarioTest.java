/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end <em>consumer-contract</em> acceptance tests for the {@code PyramidIndex} spatial
 * range query, exercised through the public {@link com.hellblazer.luciferase.lucien.AbstractSpatialIndex#entitiesInRegion(Spatial.Cube)}
 * entry point (which drives {@code spatialRangeQuery} → {@code findNodesIntersectingBounds}).
 *
 * <p><b>Why this exists (RDR-011 research, 2026-05-31).</b> Region/box queries are the canonical
 * consumer of an SFC range scan in every comparable index (SQLite R*Tree, PostGIS GiST, Google
 * S2 {@code RegionCoverer}, Uber H3 polyfill, t8code {@code t8_forest_search}). All of them share
 * the same contract: the index returns a <em>superset</em> of the entities geometrically inside
 * the region (cells straddle the boundary), and the caller applies a final exact filter. The two
 * load-bearing invariants those libraries test are:
 * <ol>
 *   <li><b>No false negatives</b> — every entity whose point lies inside the region MUST be
 *       returned. This is the correctness invariant; it must hold regardless of whether the
 *       underlying {@code findNodesIntersectingBounds} is the current O(n) scan or a future
 *       SFC-pruned (LITMAX/BIGMIN-style) implementation.</li>
 *   <li><b>Exact recovery</b> — filtering the (superset) result by true point-containment
 *       reproduces the brute-force ground truth <em>exactly</em>.</li>
 * </ol>
 * plus a <b>tightness</b> measurement (false-positive ratio) recorded as a non-fatal signal.
 *
 * <p>Ground truth is an independent brute-force linear scan over the inserted points with an
 * inclusive AABB containment test — it never calls the methods under test. All randomness is
 * seeded for determinism (project rule).
 *
 * <p>This is the regression guard for RDR-011: it pins the range-query consumer contract so that
 * a Direction-A/C SFC-range implementation can be developed test-first against it. A correct
 * SFC-pruned scan must keep every assertion here green while (separately) tightening the
 * false-positive ratio.
 *
 * @author hal.hildebrand
 */
class PyramidRangeQueryScenarioTest {

    /** Insertion level — cell size {@code 2^(21-LEVEL)} = 512, giving ~94 cells/axis over the domain. */
    private static final byte  LEVEL      = 12;
    /** Insert domain, held away from 0 and from MAX_COORD to avoid degenerate-edge artifacts. */
    private static final float DOMAIN_MIN = 1024f;
    private static final float DOMAIN_MAX = 49152f;
    private static final int   N          = 1500;
    private static final long  SEED       = 424242L;

    private PyramidIndex<LongEntityID, String> index;
    /** Ground-truth id → inserted point. */
    private final Map<LongEntityID, Point3f> truth = new HashMap<>();

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
        truth.clear();
        var rng = new Random(SEED);
        for (int i = 0; i < N; i++) {
            var p = randomDomainPoint(rng);
            var id = index.insert(p, LEVEL, "e" + i);
            truth.put(id, p);
        }
        assertEquals(N, truth.size(), "All inserts must yield distinct ids");
    }

    // ===== Load-bearing invariants over random query boxes (varied selectivity) =====

    /**
     * Over many random boxes spanning a wide selectivity range: the public range query must
     * (a) never drop an entity that is truly inside the box (no false negatives), and
     * (b) recover the brute-force set exactly after a point-containment filter.
     */
    @Test
    void randomBoxes_noFalseNegatives_andExactAfterFilter() {
        var rng = new Random(SEED + 1);
        long totalTrue = 0, totalReturned = 0;
        int boxes = 200;
        int nonEmptyBoxes = 0;

        for (int b = 0; b < boxes; b++) {
            var box = randomQueryBox(rng);
            Set<LongEntityID> groundTruth = bruteForce(box);

            Set<LongEntityID> returned = new HashSet<>(index.entitiesInRegion(box));

            // (a) No false negatives: every truly-inside entity is present.
            assertTrue(returned.containsAll(groundTruth),
                       () -> "False negative: box=" + describe(box) + " missing "
                             + missing(groundTruth, returned));

            // (b) Exact recovery: filter the superset by real point-containment → equals ground truth.
            Set<LongEntityID> filtered = new HashSet<>();
            for (var id : returned) {
                if (contains(box, truth.get(id))) {
                    filtered.add(id);
                }
            }
            assertEquals(groundTruth, filtered,
                         () -> "Exact-filter mismatch for box=" + describe(box));

            totalTrue += groundTruth.size();
            totalReturned += returned.size();
            if (!groundTruth.isEmpty()) {
                nonEmptyBoxes++;
            }
        }

        assertTrue(nonEmptyBoxes >= 30 && totalTrue > 200,
                   "Test would be vacuous: nonEmptyBoxes=" + nonEmptyBoxes + "/" + boxes
                   + ", totalTrue=" + totalTrue);
        // Tightness signal (non-fatal): superset never smaller than truth; ratio finite.
        assertTrue(totalReturned >= totalTrue, "Returned set must be a superset of ground truth overall");
        double fpRatio = totalTrue == 0 ? 0 : (double) (totalReturned - totalTrue) / totalTrue;
        // Recorded, not asserted tight: the current O(n)+AABB path over-fetches boundary cells.
        // A future SFC-pruned implementation should drive this DOWN while keeping (a)/(b) green.
        assertTrue(fpRatio >= 0.0 && Double.isFinite(fpRatio),
                   "Tightness ratio must be a finite non-negative number, got " + fpRatio);
    }

    // ===== Boundary / edge fixtures (the cases real libraries pin explicitly) =====

    /** A box covering the entire insert domain must return every inserted entity. */
    @Test
    void fullDomainBox_returnsAllEntities() {
        var box = new Spatial.Cube(DOMAIN_MIN - 1f, DOMAIN_MIN - 1f, DOMAIN_MIN - 1f,
                                   (DOMAIN_MAX - DOMAIN_MIN) + 2f);
        Set<LongEntityID> returned = new HashSet<>(index.entitiesInRegion(box));
        assertEquals(truth.keySet(), returned, "Full-domain box must return all entities");
    }

    /** A tiny box around one known inserted point must return that entity (and only it after filter). */
    @Test
    void tinyBoxAroundKnownPoint_returnsExactlyThatEntity() {
        var entry = truth.entrySet().iterator().next();
        var p = entry.getValue();
        float r = 0.25f; // sub-voxel
        var box = new Spatial.Cube(p.x - r, p.y - r, p.z - r, 2 * r);

        Set<LongEntityID> groundTruth = bruteForce(box);
        assertTrue(groundTruth.contains(entry.getKey()), "Ground truth must include the target point");

        Set<LongEntityID> returned = new HashSet<>(index.entitiesInRegion(box));
        assertTrue(returned.containsAll(groundTruth), "No false negatives for tiny box");

        Set<LongEntityID> filtered = new HashSet<>();
        for (var id : returned) {
            if (contains(box, truth.get(id))) {
                filtered.add(id);
            }
        }
        assertEquals(groundTruth, filtered, "Tiny box exact-filter must equal ground truth");
    }

    /** Points placed exactly on a box's faces and corners are inclusive and must be returned. */
    @Test
    void boundaryInclusive_facesAndCorners() {
        // Use a fresh, controlled index for exact placement.
        var idx = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        float ox = 4096f, oy = 4096f, oz = 4096f, ext = 4096f;
        var box = new Spatial.Cube(ox, oy, oz, ext);
        float mx = ox + ext, my = oy + ext, mz = oz + ext;

        List<Point3f> onBoundary = List.of(
            new Point3f(ox, oy, oz),                  // min corner
            new Point3f(mx, my, mz),                  // max corner
            new Point3f(ox, (oy + my) / 2, (oz + mz) / 2),  // -X face
            new Point3f(mx, (oy + my) / 2, (oz + mz) / 2),  // +X face
            new Point3f((ox + mx) / 2, oy, (oz + mz) / 2),  // -Y face
            new Point3f((ox + mx) / 2, my, mz)              // +Y/+Z edge
        );
        var ids = new ArrayList<LongEntityID>();
        for (int i = 0; i < onBoundary.size(); i++) {
            ids.add(idx.insert(onBoundary.get(i), LEVEL, "bnd" + i));
        }

        Set<LongEntityID> returned = new HashSet<>(idx.entitiesInRegion(box));
        for (int i = 0; i < ids.size(); i++) {
            int fi = i;
            assertTrue(returned.contains(ids.get(i)),
                       () -> "Boundary point " + onBoundary.get(fi) + " must be returned (inclusive bounds)");
        }
    }

    /** A box far from every inserted point yields no true positives after filtering. */
    @Test
    void remoteBox_noTruePositives() {
        // A 1-unit box wedged just inside the upper domain corner, far from the seeded cluster mass.
        var box = new Spatial.Cube(DOMAIN_MAX - 0.5f, DOMAIN_MAX - 0.5f, DOMAIN_MAX - 0.5f, 0.4f);
        Set<LongEntityID> groundTruth = bruteForce(box);

        Set<LongEntityID> returned = new HashSet<>(index.entitiesInRegion(box));
        // The index may return boundary-cell occupants (superset); after exact filter it must match.
        Set<LongEntityID> filtered = new HashSet<>();
        for (var id : returned) {
            if (contains(box, truth.get(id))) {
                filtered.add(id);
            }
        }
        assertEquals(groundTruth, filtered, "Remote box must have no spurious true positives");
        assertTrue(returned.containsAll(groundTruth), "No false negatives even for remote box");
    }

    /** An empty index returns an empty result for any region. */
    @Test
    void emptyIndex_returnsEmpty() {
        var empty = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        var box = new Spatial.Cube(DOMAIN_MIN, DOMAIN_MIN, DOMAIN_MIN, DOMAIN_MAX - DOMAIN_MIN);
        assertTrue(empty.entitiesInRegion(box).isEmpty(), "Empty index → empty region result");
    }

    // ===== helpers =====

    private static Point3f randomDomainPoint(Random rng) {
        float span = DOMAIN_MAX - DOMAIN_MIN;
        return new Point3f(DOMAIN_MIN + rng.nextFloat() * span,
                           DOMAIN_MIN + rng.nextFloat() * span,
                           DOMAIN_MIN + rng.nextFloat() * span);
    }

    /**
     * Random axis-aligned query box with extent drawn across a wide range (sub-voxel → ~1/4 domain)
     * so the suite exercises both high-selectivity (small box) and low-selectivity (large box) paths,
     * including boxes that straddle SFC discontinuities near the domain centre.
     */
    private static Spatial.Cube randomQueryBox(Random rng) {
        float span = DOMAIN_MAX - DOMAIN_MIN;
        // Log-uniform-ish extent: bias toward smaller boxes but include large ones.
        float frac = (float) Math.pow(10, -2 + 2 * rng.nextDouble()); // 0.01 .. 1.0
        float ext = Math.max(0.5f, frac * span * 0.25f);
        float ox = DOMAIN_MIN + rng.nextFloat() * Math.max(1f, span - ext);
        float oy = DOMAIN_MIN + rng.nextFloat() * Math.max(1f, span - ext);
        float oz = DOMAIN_MIN + rng.nextFloat() * Math.max(1f, span - ext);
        return new Spatial.Cube(ox, oy, oz, ext);
    }

    /** Inclusive AABB containment of a point in a cube. */
    private static boolean contains(Spatial.Cube box, Point3f p) {
        float mx = box.originX() + box.extent();
        float my = box.originY() + box.extent();
        float mz = box.originZ() + box.extent();
        return p.x >= box.originX() && p.x <= mx
            && p.y >= box.originY() && p.y <= my
            && p.z >= box.originZ() && p.z <= mz;
    }

    /** Independent brute-force ground truth: linear scan over inserted points. Never calls the index. */
    private Set<LongEntityID> bruteForce(Spatial.Cube box) {
        var result = new HashSet<LongEntityID>();
        for (var e : truth.entrySet()) {
            if (contains(box, e.getValue())) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    private static Set<LongEntityID> missing(Set<LongEntityID> expected, Set<LongEntityID> got) {
        var m = new HashSet<>(expected);
        m.removeAll(got);
        return m;
    }

    private static String describe(Spatial.Cube b) {
        return "Cube[(" + b.originX() + "," + b.originY() + "," + b.originZ() + ") ext=" + b.extent() + "]";
    }
}
