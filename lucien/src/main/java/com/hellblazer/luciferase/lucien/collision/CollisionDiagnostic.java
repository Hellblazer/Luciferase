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
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;

import javax.vecmath.Point3f;

/**
 * Diagnostic to understand why collisions aren't being detected.
 *
 * @author hal.hildebrand
 */
public class CollisionDiagnostic {

    private static boolean boundsOverlap(EntityBounds b1, EntityBounds b2) {
        return b1.getMaxX() >= b2.getMinX() && b1.getMinX() <= b2.getMaxX() && b1.getMaxY() >= b2.getMinY()
        && b1.getMinY() <= b2.getMaxY() && b1.getMaxZ() >= b2.getMinZ() && b1.getMinZ() <= b2.getMaxZ();
    }

    public static void main(String[] args) {
        // Create spatial index
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator(), 100,
                                                           // max entities per node
                                                           (byte) 10  // max depth
        );

        // Test 1: Two overlapping entities
        System.out.println("=== Test 1: Two overlapping entities ===");

        // Entity 1 at origin with radius 1
        Point3f pos1 = new Point3f(0, 0, 0);
        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        EntityBounds bounds1 = new EntityBounds(pos1, 1.0f);
        octree.insert(id1, pos1, (byte) 10, "Entity1", bounds1);
        octree.setCollisionShape(id1, new SphereShape(pos1, 1.0f));

        // Entity 2 overlapping with entity 1
        Point3f pos2 = new Point3f(1.5f, 0, 0); // Centers 1.5 apart, radii sum = 2.0, so they overlap
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");
        EntityBounds bounds2 = new EntityBounds(pos2, 1.0f);
        octree.insert(id2, pos2, (byte) 10, "Entity2", bounds2);
        octree.setCollisionShape(id2, new SphereShape(pos2, 1.0f));

        System.out.println("Entity 1: " + pos1 + " radius 1.0");
        System.out.println("Entity 2: " + pos2 + " radius 1.0");
        System.out.println("Distance between centers: " + pos1.distance(pos2));
        System.out.println("Sum of radii: 2.0");
        System.out.println("Should overlap: " + (pos1.distance(pos2) < 2.0));

        // Check bounds
        System.out.println("\nBounds check:");
        System.out.println("Bounds 1: " + bounds1);
        System.out.println("Bounds 2: " + bounds2);
        System.out.println("Bounds overlap: " + boundsOverlap(bounds1, bounds2));

        // Check collision detection
        System.out.println("\nCollision detection:");
        var collisions = octree.findAllCollisions();
        System.out.println("Collisions found: " + collisions.size());
        for (var collision : collisions) {
            System.out.println("  Collision between " + collision.content1() + " and " + collision.content2());
        }

        // Test 2: Check spatial nodes
        System.out.println("\n=== Test 2: Spatial node analysis ===");
        var nodes = octree.nodes().toList();
        System.out.println("Total nodes: " + nodes.size());
        for (var node : nodes) {
            System.out.println("  Node " + node.mortonIndex() + " contains: " + node.entityIds());
        }

        // Test 3: Direct collision check
        System.out.println("\n=== Test 3: Direct collision check ===");
        var directCollision = octree.checkCollision(id1, id2);
        System.out.println("Direct collision check: " + directCollision.isPresent());
        if (directCollision.isPresent()) {
            var c = directCollision.get();
            System.out.println("  Contact point: " + c.contactPoint());
            System.out.println("  Contact normal: " + c.contactNormal());
            System.out.println("  Penetration depth: " + c.penetrationDepth());
        }

        // Test 4: Check collision shapes
        System.out.println("\n=== Test 4: Collision shapes ===");
        CollisionShape shape1 = octree.getCollisionShape(id1);
        CollisionShape shape2 = octree.getCollisionShape(id2);
        System.out.println("Shape 1: " + shape1);
        System.out.println("Shape 2: " + shape2);
        if (shape1 != null && shape2 != null) {
            var shapeCollision = shape1.collidesWith(shape2);
            System.out.println("Shape collision: " + shapeCollision.collides);
            if (shapeCollision.collides) {
                System.out.println("  Contact point: " + shapeCollision.contactPoint);
                System.out.println("  Contact normal: " + shapeCollision.contactNormal);
                System.out.println("  Penetration depth: " + shapeCollision.penetrationDepth);
            }
        }
    }
}
