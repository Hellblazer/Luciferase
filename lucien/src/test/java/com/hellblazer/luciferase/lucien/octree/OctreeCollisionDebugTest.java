/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Optional;

/**
 * Debug test to understand collision detection behavior
 *
 * @author hal.hildebrand
 */
public class OctreeCollisionDebugTest {

    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void debugBasicPointCollision() {
        // Insert two point entities very close to each other
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f); // Within collision threshold

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        System.out.println("Entity 1 ID: " + id1);
        System.out.println("Entity 2 ID: " + id2);
        System.out.println("Distance: " + pos1.distance(pos2));

        // Test individual collision check
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        System.out.println("Individual collision found: " + collision.isPresent());

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        System.out.println("All collisions found: " + allCollisions.size());
        
        for (var c : allCollisions) {
            System.out.println("Collision: " + c.entityId1() + " vs " + c.entityId2() + 
                             ", depth: " + c.penetrationDepth());
        }
    }

    @Test
    void debugBoundedEntityCollision() {
        // Create two overlapping bounded entities
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(110, 110, 110);

        EntityBounds bounds1 = new EntityBounds(
            new Point3f(90, 90, 90),
            new Point3f(110, 110, 110)
        );
        EntityBounds bounds2 = new EntityBounds(
            new Point3f(100, 100, 100),
            new Point3f(120, 120, 120)
        );

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "BoundedEntity1", bounds1);
        octree.insert(id2, center2, (byte) 10, "BoundedEntity2", bounds2);

        System.out.println("Entity 1 ID: " + id1);
        System.out.println("Entity 2 ID: " + id2);
        System.out.println("Bounds 1: " + bounds1.getMinX() + "," + bounds1.getMinY() + "," + bounds1.getMinZ() +
                         " to " + bounds1.getMaxX() + "," + bounds1.getMaxY() + "," + bounds1.getMaxZ());
        System.out.println("Bounds 2: " + bounds2.getMinX() + "," + bounds2.getMinY() + "," + bounds2.getMinZ() +
                         " to " + bounds2.getMaxX() + "," + bounds2.getMaxY() + "," + bounds2.getMaxZ());

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        System.out.println("Individual collision found: " + collision.isPresent());

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        System.out.println("All collisions found: " + allCollisions.size());
        
        for (var c : allCollisions) {
            System.out.println("Collision: " + c.entityId1() + " vs " + c.entityId2() + 
                             ", depth: " + c.penetrationDepth());
        }
    }

    @Test
    void debugMixedEntityTypes() {
        // Test collision between point entity and bounded entity
        Point3f pointPos = new Point3f(105, 105, 105);
        Point3f boundedCenter = new Point3f(100, 100, 100);

        EntityBounds bounds = new EntityBounds(
            new Point3f(95, 95, 95),
            new Point3f(115, 115, 115)
        );

        LongEntityID pointId = octree.insert(pointPos, (byte) 10, "PointEntity");
        LongEntityID boundedId = idGenerator.generateID();
        octree.insert(boundedId, boundedCenter, (byte) 10, "BoundedEntity", bounds);

        System.out.println("Point Entity ID: " + pointId + " at " + pointPos);
        System.out.println("Bounded Entity ID: " + boundedId + " at " + boundedCenter);
        System.out.println("Bounds: " + bounds.getMinX() + "," + bounds.getMinY() + "," + bounds.getMinZ() +
                         " to " + bounds.getMaxX() + "," + bounds.getMaxY() + "," + bounds.getMaxZ());

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(pointId, boundedId);
        System.out.println("Individual collision found: " + collision.isPresent());

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        System.out.println("All collisions found: " + allCollisions.size());
        
        for (var c : allCollisions) {
            System.out.println("Collision: " + c.entityId1() + " vs " + c.entityId2() + 
                             ", depth: " + c.penetrationDepth());
        }
    }
}