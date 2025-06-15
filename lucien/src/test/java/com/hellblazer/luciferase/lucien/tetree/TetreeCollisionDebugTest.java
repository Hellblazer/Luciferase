/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

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
 * Debug test to understand Tetree collision detection behavior
 *
 * @author hal.hildebrand
 */
public class TetreeCollisionDebugTest {

    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void debugBasicPointCollision() {
        // Insert two point entities very close to each other
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f); // Within collision threshold

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        System.out.println("Entity 1 ID: " + id1);
        System.out.println("Entity 2 ID: " + id2);
        System.out.println("Distance: " + pos1.distance(pos2));

        // Test individual collision check
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        System.out.println("Individual collision found: " + collision.isPresent());

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
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

        LongEntityID pointId = tetree.insert(pointPos, (byte) 10, "PointEntity");
        LongEntityID boundedId = idGenerator.generateID();
        tetree.insert(boundedId, boundedCenter, (byte) 10, "BoundedEntity", bounds);

        System.out.println("Point Entity ID: " + pointId + " at " + pointPos);
        System.out.println("Bounded Entity ID: " + boundedId + " at " + boundedCenter);
        System.out.println("Bounds: " + bounds.getMinX() + "," + bounds.getMinY() + "," + bounds.getMinZ() +
                         " to " + bounds.getMaxX() + "," + bounds.getMaxY() + "," + bounds.getMaxZ());

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(pointId, boundedId);
        System.out.println("Individual collision found: " + collision.isPresent());

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        System.out.println("All collisions found: " + allCollisions.size());
        
        for (var c : allCollisions) {
            System.out.println("Collision: " + c.entityId1() + " vs " + c.entityId2() + 
                             ", depth: " + c.penetrationDepth());
        }
    }

    @Test
    void debugSpatialIndexState() {
        // Test the spatial index state
        Point3f center = new Point3f(200, 200, 200);
        EntityBounds largeBounds = new EntityBounds(
            new Point3f(150, 150, 150),
            new Point3f(250, 250, 250)
        );

        Point3f smallPos1 = new Point3f(170, 170, 170);
        Point3f smallPos2 = new Point3f(230, 230, 230);

        LongEntityID largeId = idGenerator.generateID();
        tetree.insert(largeId, center, (byte) 8, "LargeSpanningEntity", largeBounds);

        LongEntityID small1 = tetree.insert(smallPos1, (byte) 10, "SmallEntity1");
        LongEntityID small2 = tetree.insert(smallPos2, (byte) 10, "SmallEntity2");

        System.out.println("Large Entity ID: " + largeId + " at " + center);
        System.out.println("Large bounds: " + largeBounds.getMinX() + "," + largeBounds.getMinY() + "," + largeBounds.getMinZ() +
                         " to " + largeBounds.getMaxX() + "," + largeBounds.getMaxY() + "," + largeBounds.getMaxZ());
        System.out.println("Small Entity 1 ID: " + small1 + " at " + smallPos1);
        System.out.println("Small Entity 2 ID: " + small2 + " at " + smallPos2);

        // Check entity counts
        System.out.println("Total entities: " + tetree.entityCount());
        System.out.println("Total nodes: " + tetree.nodeCount());

        // Check individual collisions
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision1 = tetree.checkCollision(largeId, small1);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision2 = tetree.checkCollision(largeId, small2);
        
        System.out.println("Large vs Small1 collision: " + collision1.isPresent());
        System.out.println("Large vs Small2 collision: " + collision2.isPresent());

        // Test findCollisions for large entity
        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisionsWithLarge = tetree.findCollisions(largeId);
        System.out.println("Collisions with large entity: " + collisionsWithLarge.size());

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        System.out.println("All collisions found: " + allCollisions.size());
        
        for (var c : allCollisions) {
            System.out.println("Collision: " + c.entityId1() + " vs " + c.entityId2() + 
                             ", depth: " + c.penetrationDepth());
        }

        // Check the spatial map
        var spatialMap = tetree.getSpatialMap();
        System.out.println("Spatial map entries: " + spatialMap.size());
        for (var entry : spatialMap.entrySet()) {
            System.out.println("Node " + entry.getKey() + " contains entities: " + entry.getValue());
        }
    }
}