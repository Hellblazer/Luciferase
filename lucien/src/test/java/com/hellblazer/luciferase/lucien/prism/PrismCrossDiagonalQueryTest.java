/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-009 Phase 6 (Luciferase-fok): query ops traverse BOTH prism families across the shared S0/S1
 * diagonal — no gaps, no double-counting. A point with {@code y <= x} is in S0, {@code y > x} in S1;
 * the diagonal is now an interior face. Range and ray queries (which scan the index) and kNN /
 * collision (which BFS via neighbors) must all see entities in both halves.
 *
 * @author hal.hildebrand
 */
class PrismCrossDiagonalQueryTest {

    private Prism<LongEntityID, String> prism;

    @BeforeEach
    void setUp() {
        prism = new Prism<>(new SequentialLongIDGenerator(), 1.0f, 21);
    }

    @Test
    @DisplayName("(b) a range query spanning the diagonal returns each entity once — S0 and S1")
    void rangeSpanningDiagonalReturnsBothHalvesOnce() {
        // Mirror pairs straddling the diagonal: (a,b) in S0 (y<x) and (b,a) in S1 (y>x).
        var ids = new HashSet<LongEntityID>();
        ids.add(prism.insert(new Point3f(0.60f, 0.40f, 0.5f), (byte) 8, "S0-a")); // y<x
        ids.add(prism.insert(new Point3f(0.40f, 0.60f, 0.5f), (byte) 8, "S1-a")); // y>x
        ids.add(prism.insert(new Point3f(0.70f, 0.30f, 0.5f), (byte) 8, "S0-b"));
        ids.add(prism.insert(new Point3f(0.30f, 0.70f, 0.5f), (byte) 8, "S1-b"));

        // A region covering the whole unit cube spans the diagonal and contains all four.
        var region = new Spatial.Cube(0.0f, 0.0f, 0.0f, 1.0f);
        var found = prism.entitiesInRegion(region);
        var foundSet = new HashSet<>(found);
        assertEquals(4, foundSet.size(), "all four entities (both halves) must be returned");
        assertEquals(found.size(), foundSet.size(), "no entity returned twice (no double-count)");
        assertTrue(foundSet.containsAll(ids), "range must return both the S0 and S1 entities");
    }

    @Test
    @DisplayName("(a) a ray crossing the diagonal hits entities in both halves, each once")
    void rayCrossingDiagonalHitsBothHalves() {
        // Two entities on opposite sides of the diagonal, both near the plane y + x = 1 at z=0.5.
        var s0 = prism.insert(new Point3f(0.62f, 0.40f, 0.5f), (byte) 8, "S0"); // y<x
        var s1 = prism.insert(new Point3f(0.40f, 0.62f, 0.5f), (byte) 8, "S1"); // y>x
        // A ray along the anti-diagonal direction at z=0.5, crossing y=x.
        var ray = new Ray3D(new Point3f(0.75f, 0.27f, 0.5f), new Vector3f(-1f, 1f, 0f));
        var hits = prism.rayIntersectAll(ray);
        var hitIds = new HashSet<LongEntityID>();
        for (var h : hits) {
            hitIds.add(h.entityId());
        }
        assertTrue(hitIds.contains(s0), "ray must hit the S0 entity");
        assertTrue(hitIds.contains(s1), "ray must hit the S1 entity across the diagonal");
    }

    @Test
    @DisplayName("(c) kNN BFS crosses the diagonal: an S0 query reaches its cross-diagonal S1 face-neighbor")
    void kNNIncludesCrossDiagonalNeighbors() {
        // The addNeighboringNodes BFS hop must cross S0->S1. The two entities are in CROSS-DIAGONAL-
        // ADJACENT cells — (0.45,0.30) and its mirror (0.30,0.45) are the same diagonal cell (1,1) at
        // level 2, S0 vs S1 — so the S0 node's hypotenuse face-neighbor is exactly the S1 node,
        // reached in one BFS hop iff addNeighboringNodes crosses the diagonal (RDR-009 P6).
        //
        // NOTE: this uses adjacent cells deliberately. kNN over a SPARSE prism index (close
        // neighbours several empty cells away) under-returns regardless of the diagonal — a
        // pre-existing Prism/ASI coordinate-model gap (getCellSizeAtLevel int-truncates the
        // normalized cell size to 0, stalling the expanding-radius fallback; findNodesIntersectingBounds
        // is also a stub). That is orthogonal to family-crossing (same-half sparse kNN fails
        // identically) and is tracked separately; fixing it needs an ASI signature change.
        var s0 = prism.insert(new Point3f(0.45f, 0.30f, 0.50f), (byte) 2, "S0"); // y<x -> S0, cell (1,1)
        var s1 = prism.insert(new Point3f(0.30f, 0.45f, 0.50f), (byte) 2, "S1"); // y>x -> S1, mirror cell

        var nearest = prism.kNearestNeighbors(new Point3f(0.45f, 0.30f, 0.50f), 2, 1.0f);
        var nearestSet = new HashSet<>(nearest);
        assertTrue(nearestSet.contains(s0) && nearestSet.contains(s1),
            "kNN must reach the S1 entity across the shared diagonal (the S0 query node's hypotenuse "
            + "face-neighbor) — the BFS must cross S0->S1");
    }

    @Test
    @DisplayName("(d) adjacent-node collision crosses the diagonal: point entities in S0/S1 face-neighbor cells collide")
    void collisionDetectedAcrossDiagonal() {
        // POINT entities (no bounds) within the point-point collision threshold (0.1), in
        // cross-diagonal-ADJACENT cells (diagonal cell (1,1) at level 2, S0 vs S1 — hypotenuse
        // face-neighbors). Point entities skip the all-pairs bounded phase (Phase 2) and sit in
        // different nodes (skip the intra-node phase), so this pair can ONLY be found by the
        // adjacent-node phase (findAdjacentNodeCollisions -> addNeighboringNodes), which must cross
        // the diagonal (RDR-009 P6). Centres are 0.057 apart (< 0.1 threshold).
        var s0Id = prism.insert(new Point3f(0.40f, 0.36f, 0.50f), (byte) 2, "S0"); // y<x -> S0, cell (1,1)
        var s1Id = prism.insert(new Point3f(0.36f, 0.40f, 0.50f), (byte) 2, "S1"); // y>x -> S1, mirror cell

        var collisions = prism.findAllCollisions();
        var pairFound = collisions.stream().anyMatch(c ->
            (c.entityId1().equals(s0Id) && c.entityId2().equals(s1Id))
            || (c.entityId1().equals(s1Id) && c.entityId2().equals(s0Id)));
        assertTrue(pairFound, "the S0/S1 point pair must be detected across the diagonal via the "
            + "adjacent-node collision phase");
    }
}
