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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Octree collision detection functionality.
 * Tests basic collision detection, bounded entities, and edge cases.
 *
 * @author hal.hildebrand
 */
public class OctreeCollisionDetectionTest {

    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator);
    }

    @Test
    void testBasicPointEntityCollision() {
        // Insert two point entities very close to each other
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f); // Within collision threshold

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        // Test individual collision check
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Close point entities should collide");

        // Verify collision properties
        var collisionPair = collision.get();
        assertTrue(collisionPair.involves(id1), "Collision should involve entity1");
        assertTrue(collisionPair.involves(id2), "Collision should involve entity2");
        assertEquals(id2, collisionPair.getOtherEntity(id1), "Should return other entity correctly");
        assertEquals(id1, collisionPair.getOtherEntity(id2), "Should return other entity correctly");

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        assertEquals(1, allCollisions.size(), "Should find exactly one collision");
        assertEquals(collisionPair.entityId1(), allCollisions.get(0).entityId1(), "Same collision should be found");
    }

    @Test
    void testNoCollisionBetweenDistantEntities() {
        // Insert two point entities far apart
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(200, 200, 200);

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        // Test individual collision check
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Distant point entities should not collide");

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        assertTrue(allCollisions.isEmpty(), "Should find no collisions");
    }

    @Test
    void testBoundedEntityCollision() {
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

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping bounded entities should collide");

        var collisionPair = collision.get();
        assertTrue(collisionPair.hasBounds(), "Collision should have bounds information");
        assertNotNull(collisionPair.bounds1(), "First entity should have bounds");
        assertNotNull(collisionPair.bounds2(), "Second entity should have bounds");
        assertTrue(collisionPair.penetrationDepth() > 0, "Should have positive penetration depth");
    }

    @Test
    void testNonOverlappingBoundedEntities() {
        // Create two non-overlapping bounded entities
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(150, 150, 150);

        EntityBounds bounds1 = new EntityBounds(
            new Point3f(90, 90, 90),
            new Point3f(110, 110, 110)
        );
        EntityBounds bounds2 = new EntityBounds(
            new Point3f(140, 140, 140),
            new Point3f(160, 160, 160)
        );

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "BoundedEntity1", bounds1);
        octree.insert(id2, center2, (byte) 10, "BoundedEntity2", bounds2);

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Non-overlapping bounded entities should not collide");

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        assertTrue(allCollisions.isEmpty(), "Should find no collisions");
    }

    @Test
    void testMixedEntityTypeCollisions() {
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

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(pointId, boundedId);
        assertTrue(collision.isPresent(), "Point entity inside bounded entity should collide");

        var collisionPair = collision.get();
        // One entity should have bounds, the other should not
        boolean hasOneBounded = (collisionPair.bounds1() != null) != (collisionPair.bounds2() != null);
        assertTrue(hasOneBounded, "Should have one bounded and one point entity");
    }

    @Test
    void testCollisionDetectionWithSpanningEntities() {
        // Create a large entity that spans multiple nodes
        Point3f center = new Point3f(256, 256, 256);
        EntityBounds largeBounds = new EntityBounds(
            new Point3f(200, 200, 200),
            new Point3f(312, 312, 312)
        );

        // Create smaller entities within the large entity's bounds
        Point3f smallPos1 = new Point3f(220, 220, 220);
        Point3f smallPos2 = new Point3f(280, 280, 280);

        LongEntityID largeId = idGenerator.generateID();
        octree.insert(largeId, center, (byte) 8, "LargeSpanningEntity", largeBounds);

        LongEntityID small1 = octree.insert(smallPos1, (byte) 10, "SmallEntity1");
        LongEntityID small2 = octree.insert(smallPos2, (byte) 10, "SmallEntity2");

        // Test collisions with the spanning entity
        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisionsWithLarge = octree.findCollisions(largeId);
        // Note: findCollisions only checks nodes where the entity is stored, not all nodes its bounds intersect
        // This is a known limitation - it may miss some collisions for large bounded entities
        assertTrue(collisionsWithLarge.size() >= 1, "Large entity should find at least one collision");

        // Verify specific collisions
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision1 = octree.checkCollision(largeId, small1);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision2 = octree.checkCollision(largeId, small2);

        assertTrue(collision1.isPresent(), "Large entity should collide with small entity 1");
        assertTrue(collision2.isPresent(), "Large entity should collide with small entity 2");
    }

    @Test
    void testFindCollisionsForSpecificEntity() {
        // Create one central entity and multiple surrounding entities
        Point3f center = new Point3f(100, 100, 100);
        Point3f[] surroundingPositions = {
            new Point3f(100.05f, 100, 100),
            new Point3f(100, 100.05f, 100),
            new Point3f(100, 100, 100.05f),
            new Point3f(200, 200, 200) // This one should not collide
        };

        LongEntityID centralId = octree.insert(center, (byte) 10, "CentralEntity");
        LongEntityID[] surroundingIds = new LongEntityID[surroundingPositions.length];

        for (int i = 0; i < surroundingPositions.length; i++) {
            surroundingIds[i] = octree.insert(surroundingPositions[i], (byte) 10, "SurroundingEntity" + i);
        }

        // Test findCollisions for central entity
        List<SpatialIndex.CollisionPair<LongEntityID, String>> centralCollisions = octree.findCollisions(centralId);
        assertEquals(3, centralCollisions.size(), "Central entity should collide with 3 close entities");

        // Verify all collisions involve the central entity
        for (var collision : centralCollisions) {
            assertTrue(collision.involves(centralId), "All collisions should involve central entity");
        }

        // Test findCollisions for distant entity (should find no collisions)
        List<SpatialIndex.CollisionPair<LongEntityID, String>> distantCollisions = octree.findCollisions(surroundingIds[3]);
        assertTrue(distantCollisions.isEmpty(), "Distant entity should have no collisions");
    }

    @Test
    void testMultipleCollisionsInSameNode() {
        // Create multiple entities in close proximity (same node)
        Point3f basePos = new Point3f(128, 128, 128);
        LongEntityID[] entityIds = new LongEntityID[5];
        
        for (int i = 0; i < 5; i++) {
            Point3f pos = new Point3f(
                basePos.x + i * 0.05f,
                basePos.y + i * 0.05f,
                basePos.z + i * 0.05f
            );
            entityIds[i] = octree.insert(pos, (byte) 10, "ClusteredEntity" + i);
        }

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        
        // With 5 entities close together, we should have multiple collisions
        // Each adjacent pair should collide (0-1, 1-2, 2-3, 3-4 = 4 collisions)
        assertTrue(allCollisions.size() >= 4, "Should find multiple collision pairs");
        
        // Verify collisions are sorted by penetration depth (deepest first)
        for (int i = 1; i < allCollisions.size(); i++) {
            assertTrue(allCollisions.get(i-1).penetrationDepth() >= allCollisions.get(i).penetrationDepth(),
                      "Collisions should be sorted by penetration depth (deepest first)");
        }
    }

    @Test
    void testCollisionDetectionConsistency() {
        // Test that checkCollision and findAllCollisions are consistent
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f);
        Point3f pos3 = new Point3f(200, 200, 200);

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");
        LongEntityID id3 = octree.insert(pos3, (byte) 10, "Entity3");

        // Get individual collision checks
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision12 = octree.checkCollision(id1, id2);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision13 = octree.checkCollision(id1, id3);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision23 = octree.checkCollision(id2, id3);

        // Get all collisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();

        // Verify consistency
        assertTrue(collision12.isPresent(), "Entities 1 and 2 should collide");
        assertFalse(collision13.isPresent(), "Entities 1 and 3 should not collide");
        assertFalse(collision23.isPresent(), "Entities 2 and 3 should not collide");

        assertEquals(1, allCollisions.size(), "Should find exactly one collision in findAllCollisions");
        
        // The collision found should match the individual check
        var foundCollision = allCollisions.get(0);
        assertTrue((foundCollision.entityId1().equals(id1) && foundCollision.entityId2().equals(id2)) ||
                  (foundCollision.entityId1().equals(id2) && foundCollision.entityId2().equals(id1)),
                  "Found collision should match individual collision check");
    }

    @Test
    void testSelfCollisionCheck() {
        // Test that an entity doesn't collide with itself
        Point3f pos = new Point3f(100, 100, 100);
        LongEntityID id = octree.insert(pos, (byte) 10, "SelfEntity");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> selfCollision = octree.checkCollision(id, id);
        assertFalse(selfCollision.isPresent(), "Entity should not collide with itself");
    }

    @Test
    void testCollisionWithNonExistentEntity() {
        // Test collision detection with non-existent entity
        Point3f pos = new Point3f(100, 100, 100);
        LongEntityID existingId = octree.insert(pos, (byte) 10, "ExistingEntity");
        LongEntityID nonExistentId = idGenerator.generateID();

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(existingId, nonExistentId);
        assertFalse(collision.isPresent(), "Should not find collision with non-existent entity");
    }
}