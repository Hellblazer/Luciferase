/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link Forest#findEntitiesInRegion(Spatial)}.
 * <p>
 * Before <b>Luciferase-lgs</b>, the method silently coerced every non-cube
 * region to {@code new Spatial.Cube(0, 0, 0, 1)} and returned only entities in
 * the unit cube at the origin. These tests verify that:
 * <ol>
 *   <li>A {@link Spatial.Cube} at a non-origin position returns the entities
 *       whose positions lie in that cube (not the origin cube).</li>
 *   <li>A {@link Spatial.Sphere} returns entities inside the sphere — would
 *       have returned the origin-cube set before the fix.</li>
 *   <li>An {@link Spatial.aabb} returns entities inside the AABB — same.</li>
 *   <li>Results are aggregated across multiple trees in the forest.</li>
 *   <li>Empty regions correctly return no entities.</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class ForestFindEntitiesInRegionTest {

    private static final byte LEVEL = 10;

    private Forest<MortonKey, LongEntityID, String> forest;
    private SequentialLongIDGenerator               idGenerator;

    @BeforeEach
    void setUp() {
        forest = new Forest<>();
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void cubeAtNonOriginReturnsEntitiesInsideThatCube() {
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);

        // Inside the (100,100,100)-(150,150,150) cube
        var insideId = new LongEntityID(1);
        tree.insert(insideId, new Point3f(125, 125, 125), LEVEL, "inside");

        // Outside (at origin unit cube — what the buggy implementation would have returned)
        var atOriginId = new LongEntityID(2);
        tree.insert(atOriginId, new Point3f(0.5f, 0.5f, 0.5f), LEVEL, "origin");

        // Outside (far away)
        var farId = new LongEntityID(3);
        tree.insert(farId, new Point3f(500, 500, 500), LEVEL, "far");

        var region = new Spatial.Cube(100, 100, 100, 50);
        var results = forest.findEntitiesInRegion(region);

        assertTrue(results.contains(insideId),
                   "Entity at (125,125,125) must be returned for Cube(100,100,100,50)");
        assertFalse(results.contains(atOriginId),
                    "Entity at origin must NOT be returned (regression: Luciferase-lgs)");
        assertFalse(results.contains(farId),
                    "Entity at (500,500,500) must NOT be returned for Cube(100,100,100,50)");
    }

    @Test
    void sphereReturnsEntitiesInsideTheSphereNotTheOriginCube() {
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);

        // Inside a Sphere centered at (200,200,200), radius 30 — distance ≈ 17 to center
        var insideId = new LongEntityID(1);
        tree.insert(insideId, new Point3f(210, 210, 210), LEVEL, "inside-sphere");

        // Inside the unit cube at origin (the buggy implementation's silent default)
        var atOriginId = new LongEntityID(2);
        tree.insert(atOriginId, new Point3f(0.5f, 0.5f, 0.5f), LEVEL, "origin");

        var region = new Spatial.Sphere(200, 200, 200, 30);
        var results = forest.findEntitiesInRegion(region);

        assertTrue(results.contains(insideId),
                   "Entity inside Sphere(200,200,200,30) must be returned");
        assertFalse(results.contains(atOriginId),
                    "Origin-cube entity must NOT be returned for a non-origin Sphere "
                    + "(regression: Luciferase-lgs)");
    }

    @Test
    void aabbReturnsEntitiesInsideTheAabbNotTheOriginCube() {
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);

        // Inside the AABB (50,50,50)-(120,120,120)
        var insideId = new LongEntityID(1);
        tree.insert(insideId, new Point3f(75, 75, 75), LEVEL, "inside-aabb");

        // Inside origin cube
        var atOriginId = new LongEntityID(2);
        tree.insert(atOriginId, new Point3f(0.5f, 0.5f, 0.5f), LEVEL, "origin");

        var region = new Spatial.aabb(50, 50, 50, 120, 120, 120);
        var results = forest.findEntitiesInRegion(region);

        assertTrue(results.contains(insideId),
                   "Entity inside aabb must be returned");
        assertFalse(results.contains(atOriginId),
                    "Origin-cube entity must NOT be returned for a non-origin aabb "
                    + "(regression: Luciferase-lgs)");
    }

    @Test
    void resultsAreAggregatedAcrossMultipleTrees() {
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree1);
        forest.addTree(tree2);

        // Entity in tree1 inside the query region
        var t1Id = new LongEntityID(1);
        tree1.insert(t1Id, new Point3f(105, 105, 105), LEVEL, "tree1-inside");

        // Entity in tree2 inside the query region
        var t2Id = new LongEntityID(2);
        tree2.insert(t2Id, new Point3f(110, 110, 110), LEVEL, "tree2-inside");

        var region = new Spatial.Cube(100, 100, 100, 30);
        var results = forest.findEntitiesInRegion(region);

        assertTrue(results.contains(t1Id), "Entity from tree1 must be in aggregated results");
        assertTrue(results.contains(t2Id), "Entity from tree2 must be in aggregated results");
    }

    @Test
    void emptyRegionReturnsNoEntities() {
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);

        // Entities far from the query region
        tree.insert(new LongEntityID(1), new Point3f(50, 50, 50), LEVEL, "e1");
        tree.insert(new LongEntityID(2), new Point3f(75, 75, 75), LEVEL, "e2");

        // Query a region with no entities (far away from inserted positions)
        var region = new Spatial.Cube(500, 500, 500, 10);
        var results = forest.findEntitiesInRegion(region);

        assertEquals(0, results.size(), "Empty region must return no entities");
    }
}
