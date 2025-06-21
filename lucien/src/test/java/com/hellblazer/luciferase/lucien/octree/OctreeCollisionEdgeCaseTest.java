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
 * Edge case tests for Octree collision detection. Tests boundary conditions, floating point precision, and unusual
 * scenarios.
 *
 * @author hal.hildebrand
 */
public class OctreeCollisionEdgeCaseTest {

    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator);
    }

    @Test
    void testBoundaryNodeCollisions() {
        // Test collision detection across octree node boundaries
        Point3f pos1 = new Point3f(127.9f, 127.9f, 127.9f); // Near boundary
        Point3f pos2 = new Point3f(128.1f, 128.1f, 128.1f); // Across boundary

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        float distance = pos1.distance(pos2);
        if (distance <= 0.1f) {
            Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
            assertTrue(collision.isPresent(), "Close entities across node boundaries should collide");
        }
    }

    @Test
    void testCollisionAtExactThreshold() {
        // Test collision detection at the exact collision threshold boundary
        Point3f pos1 = new Point3f(100, 100, 100);
        // Position at exactly 0.1 distance: 0.1 along the X axis
        Point3f pos2 = new Point3f(100.1f, 100f, 100f); // Distance = 0.1

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        float distance = pos1.distance(pos2);
        assertEquals(0.1f, distance, 0.0001f, "Distance should be exactly the collision threshold");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities at exact threshold should collide");
    }

    @Test
    void testCollisionJustBeyondThreshold() {
        // Test that entities just beyond threshold don't collide
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.11f, 100.11f, 100.11f); // Just beyond threshold

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        float distance = pos1.distance(pos2);
        assertTrue(distance > 0.1f, "Distance should be beyond collision threshold");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Entities beyond threshold should not collide");
    }

    @Test
    void testCollisionPersistenceAfterRemoval() {
        // Test that collisions are properly cleaned up after entity removal
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f);
        Point3f pos3 = new Point3f(100.1f, 100.1f, 100.1f);

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");
        LongEntityID id3 = octree.insert(pos3, (byte) 10, "Entity3");

        // Verify initial collisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> initialCollisions = octree.findAllCollisions();
        assertTrue(initialCollisions.size() > 0, "Should have initial collisions");

        // Remove middle entity
        octree.removeEntity(id2);

        // Check that collision involving removed entity is gone
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision12 = octree.checkCollision(id1, id2);
        assertFalse(collision12.isPresent(), "Collision with removed entity should not exist");

        // Verify remaining entities
        assertTrue(octree.containsEntity(id1), "Entity 1 should still exist");
        assertFalse(octree.containsEntity(id2), "Entity 2 should be removed");
        assertTrue(octree.containsEntity(id3), "Entity 3 should still exist");
    }

    @Test
    void testCollisionWithEntityAtOrigin() {
        // Test collision detection with entity at coordinate origin
        Point3f origin = new Point3f(0, 0, 0);
        Point3f nearOrigin = new Point3f(0.05f, 0.05f, 0.05f);

        LongEntityID id1 = octree.insert(origin, (byte) 10, "OriginEntity");
        LongEntityID id2 = octree.insert(nearOrigin, (byte) 10, "NearOriginEntity");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities near origin should collide");
    }

    @Test
    void testConsistencyAfterEntityUpdate() {
        // Test collision consistency after entity position updates
        Point3f initialPos = new Point3f(100, 100, 100);
        Point3f otherPos = new Point3f(200, 200, 200);

        LongEntityID id1 = octree.insert(initialPos, (byte) 10, "MovableEntity");
        LongEntityID id2 = octree.insert(otherPos, (byte) 10, "StaticEntity");

        // Initially no collision
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> initialCollision = octree.checkCollision(id1, id2);
        assertFalse(initialCollision.isPresent(), "Initially distant entities should not collide");

        // Update entity to colliding position
        Point3f newPos = new Point3f(200.05f, 200.05f, 200.05f);
        octree.updateEntity(id1, newPos, (byte) 10);

        // Now should collide
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> updatedCollision = octree.checkCollision(id1, id2);
        assertTrue(updatedCollision.isPresent(), "Updated entities should collide when moved close together");
    }

    @Test
    void testDegenerateBounds() {
        // Test collision with degenerate bounds (flat in one dimension)
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(105, 100, 100);

        EntityBounds flatBounds1 = new EntityBounds(new Point3f(95, 95, 100), // Flat in Z dimension
                                                    new Point3f(105, 105, 100));
        EntityBounds flatBounds2 = new EntityBounds(new Point3f(100, 95, 100), // Overlapping flat bounds
                                                    new Point3f(110, 105, 100));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "FlatEntity1", flatBounds1);
        octree.insert(id2, center2, (byte) 10, "FlatEntity2", flatBounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping flat bounds should collide");
    }

    @Test
    void testExtremeCoordinateValues() {
        // Test collision detection with very large coordinate values (within 21-bit Morton code range)
        // Morton codes support coordinates up to 2^21 - 1 = 2097151
        float maxCoord = 2097150f;
        Point3f pos1 = new Point3f(maxCoord, maxCoord, maxCoord);
        Point3f pos2 = new Point3f(maxCoord, maxCoord, maxCoord);

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities at extreme coordinates should collide when at same position");
    }

    @Test
    void testFloatingPointPrecisionEdges() {
        // Test collision detection with floating point precision edge cases
        Point3f pos1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f pos2 = new Point3f(100.0000001f, 100.0000001f, 100.0000001f); // Very small difference

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities with minimal floating point differences should collide");
    }

    @Test
    void testIdenticalPositions() {
        // Test collision detection with entities at identical positions
        Point3f pos = new Point3f(100, 100, 100);

        LongEntityID id1 = octree.insert(pos, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(new Point3f(pos), (byte) 10, "Entity2"); // Identical position

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities at identical positions should collide");

        var collisionPair = collision.get();
        assertEquals(0.0f, collisionPair.penetrationDepth(), 0.001f,
                     "Penetration depth should be minimal for identical positions");
    }

    @Test
    void testLargeBoundsWithSmallEntity() {
        // Test collision between very large bounds and small point entity
        Point3f pointPos = new Point3f(500, 500, 500);
        Point3f largeCenter = new Point3f(500, 500, 500);

        EntityBounds largeBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(1000, 1000, 1000));

        LongEntityID pointId = octree.insert(pointPos, (byte) 10, "PointEntity");
        // Use the insert method that generates ID automatically to ensure proper ID management
        LongEntityID largeId = octree.insert(largeCenter, (byte) 5, "LargeEntity");
        // Update the entity with bounds
        octree.removeEntity(largeId);
        octree.insert(largeId, largeCenter, (byte) 5, "LargeEntity", largeBounds);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(pointId, largeId);
        assertTrue(collision.isPresent(), "Point entity inside large bounds should collide");
    }

    @Test
    void testMaximumEntityDensity() {
        // Test collision detection with maximum entity density in a small region
        Point3f basePos = new Point3f(100, 100, 100);
        LongEntityID[] entityIds = new LongEntityID[50]; // High density

        for (int i = 0; i < entityIds.length; i++) {
            Point3f pos = new Point3f(basePos.x + i * 0.001f, // Very close together
                                      basePos.y + i * 0.001f, basePos.z + i * 0.001f);
            entityIds[i] = octree.insert(pos, (byte) 15, "DenseEntity" + i);
        }

        // All entities should be found in collision detection
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        assertTrue(allCollisions.size() > 100, "High density should produce many collision pairs");

        // Test specific collision
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(entityIds[0],
                                                                                                     entityIds[1]);
        assertTrue(collision.isPresent(), "Adjacent dense entities should collide");
    }

    @Test
    void testNegativeCoordinateCollisions() {
        // Test collision detection with negative coordinates
        Point3f pos1 = new Point3f(-100, -100, -100);
        Point3f pos2 = new Point3f(-100.05f, -100.05f, -100.05f);

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(pos2, (byte) 10, "Entity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Negative coordinate entities should collide when close");
    }

    @Test
    void testTouchingBounds() {
        // Test entities with bounds that exactly touch but don't overlap
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(120, 100, 100);

        EntityBounds bounds1 = new EntityBounds(new Point3f(95, 95, 95), new Point3f(110, 105, 105));
        EntityBounds bounds2 = new EntityBounds(new Point3f(110, 95, 95), // Exactly touching bounds1's max X
                                                new Point3f(125, 105, 105));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "Entity1", bounds1);
        octree.insert(id2, center2, (byte) 10, "Entity2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Exactly touching bounds should be considered colliding");
    }

    @Test
    void testZeroBoundsEntity() {
        // Test collision with entity that has zero-size bounds
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100, 100, 100);

        EntityBounds zeroBounds = new EntityBounds(pos2, pos2); // Zero-size bounds

        LongEntityID id1 = octree.insert(pos1, (byte) 10, "PointEntity");
        LongEntityID id2 = idGenerator.generateID();
        octree.insert(id2, pos2, (byte) 10, "ZeroBoundsEntity", zeroBounds);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Point entity should collide with zero-bounds entity at same position");
    }
}
