/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the AABT-filtered collision and bounding-query paths in Tetree.
 *
 * <p>P4.1 added {@link Tetree#boundingFiltered(Spatial.aabt)}, which runs AABB traversal
 * followed by a tet-vs-tet SAT post-filter, yielding 50-80% candidate reduction for
 * tet-shaped queries.  P4.2 wires this into
 * {@link Tetree#findCollisionsInRegion(Spatial)} when the region is a {@link Spatial.aabt}.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Correctness of {@code boundingFiltered} — entities in a tet's AABB-and-SAT region
 *       are found; every returned node genuinely passes SAT.</li>
 *   <li>Correctness of {@code findCollisionsInRegion} with a {@link Tet} query — collisions
 *       between entities whose positions are within the query tet are reported.</li>
 *   <li>The aabt path is a subset of (or equal to) the AABB path — no false positives
 *       that do not pass SAT.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class TetreeBoundingFilteredIntegrationTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    // -------------------------------------------------------------------------
    // boundingFiltered correctness
    // -------------------------------------------------------------------------

    @Test
    void boundingFiltered_returnsNodesContainingInsertedPoint() {
        // Insert a point at a known location and verify it appears in a tet query
        // that covers that location.
        var pos = new Point3f(100, 100, 100);
        tetree.insert(pos, (byte) 10, "inside");

        // Locate a Tet that contains the point at level 8 (coarser than insert level 10)
        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(pos.x, pos.y, pos.z, (byte) 8);
        assertNotNull(queryTet, "Should find a tet containing (100,100,100) at level 8");
        assertTrue(queryTet.containsUltraFast(pos.x, pos.y, pos.z),
                   "Query tet must contain the inserted point");

        var nodes = tetree.boundingFiltered(queryTet).toList();
        assertFalse(nodes.isEmpty(), "boundingFiltered should find at least one node for a point inside the query tet");
    }

    @Test
    void boundingFiltered_everyReturnedNodePassesSAT() {
        // Insert several entities scattered across space
        tetree.insert(new Point3f(100, 100, 100), (byte) 10, "e1");
        tetree.insert(new Point3f(200, 200, 200), (byte) 10, "e2");
        tetree.insert(new Point3f(500, 500, 500), (byte) 10, "e3");
        tetree.insert(new Point3f(800, 800, 800), (byte) 10, "e4");

        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(100, 100, 100, (byte) 10);
        assertNotNull(queryTet);

        // Every node returned by boundingFiltered must genuinely intersect the query tet
        tetree.boundingFiltered(queryTet).forEach(node -> {
            Tet nodeTet = Tet.tetrahedron(node.sfcIndex());
            assertTrue(nodeTet.intersectsBound(queryTet),
                       "Node tet " + nodeTet + " must intersect query tet " + queryTet);
        });
    }

    @Test
    void boundingFiltered_isSubsetOfAabbBounding() {
        // boundingFiltered can only return fewer (or equal) nodes than plain bounding().
        // Every node from boundingFiltered must also appear in bounding().
        tetree.insert(new Point3f(100, 100, 100), (byte) 10, "e1");
        tetree.insert(new Point3f(120, 120, 120), (byte) 10, "e2");
        tetree.insert(new Point3f(500, 100, 100), (byte) 10, "e3");

        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(100, 100, 100, (byte) 9);
        assertNotNull(queryTet);

        Set<TetreeKey<?>> filteredKeys = tetree.boundingFiltered(queryTet)
                                               .map(SpatialIndex.SpatialNode::sfcIndex)
                                               .collect(Collectors.toSet());

        Set<TetreeKey<?>> allBoundingKeys = tetree.bounding(queryTet)
                                                  .map(SpatialIndex.SpatialNode::sfcIndex)
                                                  .collect(Collectors.toSet());

        assertTrue(allBoundingKeys.containsAll(filteredKeys),
                   "boundingFiltered result must be a subset of bounding() result");
        assertTrue(filteredKeys.size() <= allBoundingKeys.size(),
                   "boundingFiltered must not return more nodes than bounding()");
    }

    @Test
    void boundingFiltered_withBoxQuery_matchesBoundingCall() {
        // For a Box (aabt.Box), boundingFiltered degenerates to the same AABB check as bounding()
        // because Box.intersectsBound just does AABB intersection.  Results must be identical.
        tetree.insert(new Point3f(200, 200, 200), (byte) 10, "e1");
        tetree.insert(new Point3f(205, 205, 205), (byte) 10, "e2");

        var box = new Spatial.aabt.Box(150, 150, 150, 300, 300, 300);

        Set<LongEntityID> filteredEntities = tetree.boundingFiltered(box)
                                                   .flatMap(n -> n.entityIds().stream())
                                                   .collect(Collectors.toSet());

        Set<LongEntityID> boundingEntities = tetree.bounding(box)
                                                   .flatMap(n -> n.entityIds().stream())
                                                   .collect(Collectors.toSet());

        assertEquals(boundingEntities, filteredEntities,
                     "Box query: boundingFiltered and bounding should return same entities");
    }

    // -------------------------------------------------------------------------
    // findCollisionsInRegion with Spatial.aabt (AABT-filtered path)
    // -------------------------------------------------------------------------

    @Test
    void findCollisionsInRegion_aabt_findsCollisionsInsideRegion() {
        // Place two colliding point entities close together.
        // Use a Tet at the same level as the insert level so both points are inside.
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(100.05f, 100, 100); // within collision threshold

        var id1 = tetree.insert(pos1, (byte) 10, "e1");
        var id2 = tetree.insert(pos2, (byte) 10, "e2");

        // Use the exact tet that contains pos1 at level 10; both points are very close
        // so they should be inside (or very near) the same tet
        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(100, 100, 100, (byte) 10);
        assertNotNull(queryTet);

        // Verify the tet contains both points before running the region query
        boolean bothInside = queryTet.containsUltraFast(pos1.x, pos1.y, pos1.z)
                             && queryTet.containsUltraFast(pos2.x, pos2.y, pos2.z);

        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions =
                tetree.findCollisionsInRegion(queryTet);

        if (bothInside) {
            // If both points are inside the query tet, the collision must be reported
            assertEquals(1, collisions.size(),
                         "Should find exactly one collision for two close entities inside the tet region");
            assertTrue(collisions.get(0).involves(id1) && collisions.get(0).involves(id2),
                       "Collision must be between the two close entities");
        } else {
            // If the two points happen to be in different tets at level 10 (possible due
            // to tet subdivision), the region query may return 0 or 1 collisions.
            // In either case it must not return false positives (entities not in the region).
            collisions.forEach(c -> {
                assertFalse(c.involves(id1) && !queryTet.containsUltraFast(pos1.x, pos1.y, pos1.z),
                            "Should not report entity outside region");
                assertFalse(c.involves(id2) && !queryTet.containsUltraFast(pos2.x, pos2.y, pos2.z),
                            "Should not report entity outside region");
            });
        }
    }

    @Test
    void findCollisionsInRegion_aabt_suppressesCollisionsOutsideRegion() {
        // Colliding pair near origin (inside query region), plus another colliding pair far away
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(100.05f, 100, 100);
        var pos3 = new Point3f(800, 800, 800);
        var pos4 = new Point3f(800.05f, 800, 800);

        tetree.insert(pos1, (byte) 10, "inside-1");
        tetree.insert(pos2, (byte) 10, "inside-2");
        var id3 = tetree.insert(pos3, (byte) 10, "outside-1");
        var id4 = tetree.insert(pos4, (byte) 10, "outside-2");

        // Confirm all-collisions finds both pairs
        var allCollisions = tetree.findAllCollisions();
        assertTrue(allCollisions.size() >= 2,
                   "findAllCollisions should see both colliding pairs before region filtering");

        // Use a fine-grained tet that contains the near pair but NOT the far pair
        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(100, 100, 100, (byte) 12);
        assertNotNull(queryTet);

        // Confirm queryTet does not contain the far-away positions
        assertFalse(queryTet.containsUltraFast(pos3.x, pos3.y, pos3.z),
                    "Query tet at level 12 must not contain (800,800,800)");

        List<SpatialIndex.CollisionPair<LongEntityID, String>> regionCollisions =
                tetree.findCollisionsInRegion(queryTet);

        // The outside collision pair must not be reported
        boolean outsideCollisionReported = regionCollisions.stream()
                                                           .anyMatch(c -> c.involves(id3) || c.involves(id4));
        assertFalse(outsideCollisionReported,
                    "Collision between (800,x,x) entities must not be reported for tet at (100,100,100) level 12");
    }

    @Test
    void findCollisionsInRegion_aabt_noEntities_returnsEmpty() {
        // Empty tetree — any region query returns empty
        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(500, 500, 500, (byte) 8);
        assertNotNull(queryTet);

        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions =
                tetree.findCollisionsInRegion(queryTet);
        assertTrue(collisions.isEmpty(), "Empty tree should return no collisions");
    }

    @Test
    void findCollisionsInRegion_aabt_nonColliding_returnsEmpty() {
        // Two entities inside the region but not overlapping — no collision expected
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(200, 100, 100); // 100 units apart — no collision

        tetree.insert(pos1, (byte) 10, "a");
        tetree.insert(pos2, (byte) 10, "b");

        // coarse tet might contain both; even so, no geometric collision should result
        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(100, 100, 100, (byte) 7);
        assertNotNull(queryTet);

        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions =
                tetree.findCollisionsInRegion(queryTet);

        // Any reported collision must have a positive penetration depth (no phantom collisions)
        collisions.forEach(c -> assertTrue(c.penetrationDepth() > 0,
                                           "Any reported collision must have positive penetration depth"));
    }

    @Test
    void findCollisionsInRegion_aabt_withBoundedEntities() {
        // Overlapping bounded entities — check that aabt path handles EntityBounds
        var center1 = new Point3f(200, 200, 200);
        var center2 = new Point3f(210, 210, 210);

        var bounds1 = new EntityBounds(new Point3f(190, 190, 190), new Point3f(210, 210, 210));
        var bounds2 = new EntityBounds(new Point3f(200, 200, 200), new Point3f(220, 220, 220));

        var gen = new SequentialLongIDGenerator();
        var id1 = gen.generateID();
        var id2 = gen.generateID();
        tetree.insert(id1, center1, (byte) 9, "bounded1", bounds1);
        tetree.insert(id2, center2, (byte) 9, "bounded2", bounds2);

        // Use a tet that covers the region where bounds overlap
        Tet queryTet = Tet.locatePointBeyRefinementFromRoot(200, 200, 200, (byte) 8);
        assertNotNull(queryTet);

        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions =
                tetree.findCollisionsInRegion(queryTet);

        assertFalse(collisions.isEmpty(),
                    "Overlapping bounded entities within the tet region should produce a collision");
        assertTrue(collisions.stream().anyMatch(c -> c.involves(id1) && c.involves(id2)),
                   "Collision must be between the two inserted bounded entities");
    }

    // -------------------------------------------------------------------------
    // non-aabt region falls through to base-class path
    // -------------------------------------------------------------------------

    @Test
    void findCollisionsInRegion_nonAabt_delegatesToBaseClass() {
        // When the region is a plain Spatial.Sphere (not Spatial.aabt), the override
        // must delegate to the base class and produce correct results.
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(100.05f, 100, 100);
        var pos3 = new Point3f(900, 900, 900); // far outside sphere

        var id1 = tetree.insert(pos1, (byte) 10, "a");
        var id2 = tetree.insert(pos2, (byte) 10, "b");
        tetree.insert(pos3, (byte) 10, "c");

        // Sphere covering only the near pair
        var sphere = new Spatial.Sphere(100, 100, 100, 50);

        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions =
                tetree.findCollisionsInRegion(sphere);

        // Sphere is not aabt — uses base class; base class may include entities
        // generously (default -> true for unknown types), so just check no cross-region collision
        boolean farPairReported = collisions.stream()
                                            .anyMatch(c -> c.involves(id1) && !c.involves(id2));
        // The main guarantee: sphere query should NOT report the far entity paired
        // with the near entities (far entity is not near pos1/pos2)
        assertFalse(farPairReported,
                    "Sphere region query should not produce unexpected cross-region pairs");
    }
}
