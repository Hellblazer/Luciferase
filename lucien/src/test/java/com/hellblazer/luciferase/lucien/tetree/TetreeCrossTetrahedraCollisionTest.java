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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the Tetree-specific same-grid-cell cross-tetrahedron collision path (RDR-008 P5).
 *
 * <p>{@code Tetree.findAllCollisions()} overrides the base implementation to scan, for each populated node, the
 * other five characteristic tetrahedra (S0..S5) in the same grid cell — collisions between entities that share a
 * grid cell but live in different tet types are invisible to the generic neighbor-traversal path the
 * {@code AbstractSpatialIndex} base implementation uses. The generic lucien collision suite cannot exercise this
 * path (its assumptions are octree-shaped), so without this test the override's behavior is unverified.
 *
 * <p>P5 (Luciferase-x5i.10) refactors {@code Tetree.findAllCollisions} to reach the nucleus through the
 * façade-sanctioned protected re-exposure path; this test pins the same-cell cross-tetrahedron semantics so any
 * regression in the refactor surfaces immediately. The test must be GREEN both before and after the P5 refactor.
 *
 * @author hal.hildebrand
 */
public class TetreeCrossTetrahedraCollisionTest {

    /**
     * Two point entities placed inside the same grid cell at the deepest refinement level (cellSize=1) but
     * intentionally on opposite sides of the S0/S4 type boundary, within the point-collision threshold (0.1f
     * default). The base {@code findAllCollisions} sees them in different SFC nodes (different tet types) and
     * does not consider them neighbors; only the Tetree override's same-cell sweep can find their collision.
     *
     * <p>S0 contains {@code x>=y>=z}; S4 contains {@code x>=z>=y} (per {@link Tet#contains12DOP}). Positions
     * (0.5, 0.45, 0.4) and (0.5, 0.4, 0.45) land in S0 and S4 respectively and are sqrt(0.0025+0.0025) ≈ 0.071
     * apart — under the 0.1f point-collision threshold {@code AbstractSpatialIndex#checkPointCollision} uses.
     */
    @Test
    void sameGridCellDifferentTetTypesCollide() {
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());

        // Use the deepest level so cellSize=1 — at coarser levels the centroids of different tet types in a
        // single cell are spread well beyond the 0.1f point-collision threshold and we cannot exercise the
        // cross-tet path with point entities.
        byte level = 21;
        var p1 = new Point3f(0.5f, 0.45f, 0.4f);   // S0: x>=y>=z
        var p2 = new Point3f(0.5f, 0.4f, 0.45f);   // S4: x>=z>=y

        var id1 = tetree.insert(p1, level, "A");
        var id2 = tetree.insert(p2, level, "B");

        // Sanity: the two entities must actually land in different tet types of the same grid cell. Without
        // this preflight a future change to locate()/contains12DOP could silently collapse the entities into
        // the same tet and make the test pass for the wrong reason.
        var tet1 = tetree.locate(p1, level);
        var tet2 = tetree.locate(p2, level);
        assertEquals(tet1.x(), tet2.x(), "Both entities must occupy the same grid cell (x)");
        assertEquals(tet1.y(), tet2.y(), "Both entities must occupy the same grid cell (y)");
        assertEquals(tet1.z(), tet2.z(), "Both entities must occupy the same grid cell (z)");
        assertEquals(tet1.l(), tet2.l(), "Both entities must occupy the same level");
        assertNotEquals(tet1.type(), tet2.type(),
                        "Test requires entities in DIFFERENT tet types of the SAME cell — fixture is broken");
        assertNotEquals(tet1.tmIndex(), tet2.tmIndex(),
                        "Different tet types must yield different SFC keys — fixture is broken");

        // The Tetree override of findAllCollisions must surface this cross-tet pair. With point entities under
        // the 0.1f threshold and distinct SFC keys, this collision is invisible to the generic intra-node and
        // neighbor-traversal paths the base findAllCollisions uses; only the override's same-cell sweep finds
        // it.
        List<CollisionPair<LongEntityID, String>> collisions = tetree.findAllCollisions();
        assertEquals(1, collisions.size(),
                     "Tetree.findAllCollisions must find the cross-tet pair via the same-grid-cell sweep");
        var pair = collisions.get(0);
        assertTrue(pair.involves(id1), "Collision must involve entity A");
        assertTrue(pair.involves(id2), "Collision must involve entity B");
    }

    /**
     * Same-cell cross-tetrahedron collisions for all 15 unordered pairs across the six S0..S5 types. Places one
     * entity inside each of the six characteristic tetrahedra of the same grid cell, all within mutual
     * point-collision threshold. Confirms the override's loop over the five non-self types per node yields
     * symmetric coverage — every pair fires exactly once.
     *
     * <p>The six positions hug a tight neighborhood around (0.5, 0.5, 0.5) at level 21 (cellSize=1) with
     * coordinate ordering chosen so each lands in exactly one S-type per {@link Tet#contains12DOP}. Pairwise
     * distances stay under the 0.1f threshold.
     */
    @Test
    void sameGridCellAllSixTetTypesCollidePairwise() {
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());

        byte level = 21;
        // Six positions inside the unit cell at (0,0,0,21). Each ordering hits exactly one type, derived from
        // Tet.contains12DOP (t8code dtet labeling, RDR-010 Luciferase-4pd): t0=x>z>y, t1=x>y>z, t2=y>x>z,
        // t3=y>z>x, t4=z>y>x, t5=z>x>y. We use (a, a+δ, a+2δ) permutations with a=0.5 and δ=0.02 so all six
        // points stay inside the cell, hit the intended type, and lie within sqrt(2)·2δ ≈ 0.057 of each
        // other (under the 0.1f threshold).
        float a = 0.5f, d = 0.02f;
        var positions = new Point3f[]{ new Point3f(a + 2 * d, a, a + d),         // t0: x>z>y
                                       new Point3f(a + 2 * d, a + d, a),         // t1: x>y>z
                                       new Point3f(a + d, a + 2 * d, a),         // t2: y>x>z
                                       new Point3f(a, a + 2 * d, a + d),         // t3: y>z>x
                                       new Point3f(a, a + d, a + 2 * d),         // t4: z>y>x
                                       new Point3f(a + d, a, a + 2 * d) };       // t5: z>x>y

        var ids = new LongEntityID[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = tetree.insert(positions[i], level, "T" + i);
        }

        // Sanity: each entity must land in the expected unique S-type.
        var seenTypes = new boolean[6];
        for (int i = 0; i < 6; i++) {
            var tet = tetree.locate(positions[i], level);
            assertEquals(0, tet.x(), "Entity " + i + " must be in the (0,0,0) grid cell (x)");
            assertEquals(0, tet.y(), "Entity " + i + " must be in the (0,0,0) grid cell (y)");
            assertEquals(0, tet.z(), "Entity " + i + " must be in the (0,0,0) grid cell (z)");
            assertEquals(level, tet.l(), "Entity " + i + " must be at the test level");
            assertEquals((byte) i, tet.type(),
                         "Entity " + i + " must land in tet type S" + i + " — fixture is broken");
            seenTypes[tet.type()] = true;
        }
        for (byte t = 0; t < 6; t++) {
            assertTrue(seenTypes[t], "Fixture must populate tet type S" + t);
        }

        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions = tetree.findAllCollisions();
        // C(6,2) = 15 unordered pairs, every pair within collision threshold.
        assertEquals(15, collisions.size(),
                     "Override must surface every cross-tet pair across all 6 same-cell types (15 = C(6,2))");
    }
}
