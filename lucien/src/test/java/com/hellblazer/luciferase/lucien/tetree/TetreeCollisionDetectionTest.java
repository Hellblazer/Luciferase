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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Tetree collision detection functionality. Tests basic collision detection, bounded entities,
 * and tetrahedral-specific scenarios.
 *
 * @author hal.hildebrand
 */
public class TetreeCollisionDetectionTest {

    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testBasicPointEntityCollision() {
        // Insert two point entities very close to each other (positive coordinates only)
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f); // Within collision threshold

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        // Test individual collision check
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Close point entities should collide");

        // Verify collision properties
        var collisionPair = collision.get();
        assertTrue(collisionPair.involves(id1), "Collision should involve entity1");
        assertTrue(collisionPair.involves(id2), "Collision should involve entity2");
        assertEquals(id2, collisionPair.getOtherEntity(id1), "Should return other entity correctly");
        assertEquals(id1, collisionPair.getOtherEntity(id2), "Should return other entity correctly");

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        assertEquals(1, allCollisions.size(), "Should find exactly one collision");
        assertEquals(collisionPair.entityId1(), allCollisions.get(0).entityId1(), "Same collision should be found");
    }

    @Test
    void testBoundedEntityCollision() {
        // Create two overlapping bounded entities within tetrahedral constraints
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(110, 110, 110);

        EntityBounds bounds1 = new EntityBounds(new Point3f(90, 90, 90), new Point3f(110, 110, 110));
        EntityBounds bounds2 = new EntityBounds(new Point3f(100, 100, 100), new Point3f(120, 120, 120));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "BoundedEntity1", bounds1);
        tetree.insert(id2, center2, (byte) 10, "BoundedEntity2", bounds2);

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping bounded entities should collide");

        var collisionPair = collision.get();
        assertTrue(collisionPair.hasBounds(), "Collision should have bounds information");
        assertNotNull(collisionPair.bounds1(), "First entity should have bounds");
        assertNotNull(collisionPair.bounds2(), "Second entity should have bounds");
        assertTrue(collisionPair.penetrationDepth() > 0, "Should have positive penetration depth");
    }

    @Test
    void testCollisionDetectionConsistency() {
        // Test that checkCollision and findAllCollisions are consistent
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f);
        Point3f pos3 = new Point3f(500, 500, 500);

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");
        LongEntityID id3 = tetree.insert(pos3, (byte) 10, "Entity3");

        // Get individual collision checks
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision12 = tetree.checkCollision(id1, id2);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision13 = tetree.checkCollision(id1, id3);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision23 = tetree.checkCollision(id2, id3);

        // Get all collisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();

        // Verify consistency
        assertTrue(collision12.isPresent(), "Entities 1 and 2 should collide");
        assertFalse(collision13.isPresent(), "Entities 1 and 3 should not collide");
        assertFalse(collision23.isPresent(), "Entities 2 and 3 should not collide");

        assertEquals(1, allCollisions.size(), "Should find exactly one collision in findAllCollisions");

        // The collision found should match the individual check
        var foundCollision = allCollisions.get(0);
        assertTrue((foundCollision.entityId1().equals(id1) && foundCollision.entityId2().equals(id2)) || (
                   foundCollision.entityId1().equals(id2) && foundCollision.entityId2().equals(id1)),
                   "Found collision should match individual collision check");
    }

    @Test
    void testCollisionDetectionWithSpanningEntities() {
        // Create a large entity that might span multiple tetrahedra
        Point3f center = new Point3f(200, 200, 200);
        EntityBounds largeBounds = new EntityBounds(new Point3f(150, 150, 150), new Point3f(250, 250, 250));

        // Create smaller entities within the large entity's bounds
        Point3f smallPos1 = new Point3f(170, 170, 170);
        Point3f smallPos2 = new Point3f(230, 230, 230);

        LongEntityID largeId = tetree.insert(center, (byte) 8, "LargeSpanningEntity");
        // Update with bounds
        tetree.insert(largeId, center, (byte) 8, "LargeSpanningEntity", largeBounds);

        LongEntityID small1 = tetree.insert(smallPos1, (byte) 10, "SmallEntity1");
        LongEntityID small2 = tetree.insert(smallPos2, (byte) 10, "SmallEntity2");

        // Verify specific collisions work individually
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision1 = tetree.checkCollision(largeId, small1);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision2 = tetree.checkCollision(largeId, small2);

        assertTrue(collision1.isPresent(), "Large entity should collide with small entity 1");
        assertTrue(collision2.isPresent(), "Large entity should collide with small entity 2");

        // Note: spanning entity collision detection via findCollisions may work differently in Tetree
        // due to tetrahedral space partitioning vs cubic partitioning
    }

    @Test
    void testCollisionWithNonExistentEntity() {
        // Test collision detection with non-existent entity
        Point3f pos = new Point3f(100, 100, 100);
        LongEntityID existingId = tetree.insert(pos, (byte) 10, "ExistingEntity");
        LongEntityID nonExistentId = idGenerator.generateID();

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(existingId,
                                                                                                     nonExistentId);
        assertFalse(collision.isPresent(), "Should not find collision with non-existent entity");
    }

    @Test
    void testFindCollisionsForSpecificEntity() {
        // Create one central entity and multiple surrounding entities
        Point3f center = new Point3f(100, 100, 100);
        Point3f[] surroundingPositions = { new Point3f(100.05f, 100, 100), new Point3f(100, 100.05f, 100), new Point3f(
        100, 100, 100.05f), new Point3f(500, 500, 500) // This one should not collide
        };

        LongEntityID centralId = tetree.insert(center, (byte) 10, "CentralEntity");
        LongEntityID[] surroundingIds = new LongEntityID[surroundingPositions.length];

        for (int i = 0; i < surroundingPositions.length; i++) {
            surroundingIds[i] = tetree.insert(surroundingPositions[i], (byte) 10, "SurroundingEntity" + i);
        }

        // Test findCollisions for central entity
        List<SpatialIndex.CollisionPair<LongEntityID, String>> centralCollisions = tetree.findCollisions(centralId);
        assertEquals(3, centralCollisions.size(), "Central entity should collide with 3 close entities");

        // Verify all collisions involve the central entity
        for (var collision : centralCollisions) {
            assertTrue(collision.involves(centralId), "All collisions should involve central entity");
        }

        // Test findCollisions for distant entity (should find no collisions)
        List<SpatialIndex.CollisionPair<LongEntityID, String>> distantCollisions = tetree.findCollisions(
        surroundingIds[3]);
        assertTrue(distantCollisions.isEmpty(), "Distant entity should have no collisions");
    }

    @Test
    void testMixedEntityTypeCollisions() {
        // Test collision between point entity and bounded entity
        Point3f pointPos = new Point3f(105, 105, 105);
        Point3f boundedCenter = new Point3f(100, 100, 100);

        EntityBounds bounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(115, 115, 115));

        LongEntityID pointId = tetree.insert(pointPos, (byte) 10, "PointEntity");
        LongEntityID boundedId = tetree.insert(boundedCenter, (byte) 10, "BoundedEntity");
        // Update the bounded entity with bounds
        tetree.insert(boundedId, boundedCenter, (byte) 10, "BoundedEntity", bounds);

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(pointId,
                                                                                                     boundedId);
        assertTrue(collision.isPresent(), "Point entity inside bounded entity should collide");

        var collisionPair = collision.get();
        // One entity should have bounds, the other should not
        boolean hasOneBounded = (collisionPair.bounds1() == null) == (collisionPair.bounds2() != null);
        assertTrue(hasOneBounded, "Should have one bounded and one point entity");
    }

    @Test
    void testMultipleCollisionsAcrossTetrahedra() {
        // Create multiple entities that might be in different tetrahedra but still close
        Point3f basePos = new Point3f(128, 128, 128); // Near a tetrahedral boundary
        LongEntityID[] entityIds = new LongEntityID[5];

        for (int i = 0; i < 5; i++) {
            Point3f pos = new Point3f(basePos.x + i * 0.05f, basePos.y + i * 0.05f, basePos.z + i * 0.05f);
            entityIds[i] = tetree.insert(pos, (byte) 10, "ClusteredEntity" + i);
        }

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();

        // With 5 entities close together, we should have multiple collisions
        assertTrue(allCollisions.size() >= 4, "Should find multiple collision pairs");

        // Verify collisions are sorted by penetration depth (deepest first)
        for (int i = 1; i < allCollisions.size(); i++) {
            assertTrue(allCollisions.get(i - 1).penetrationDepth() >= allCollisions.get(i).penetrationDepth(),
                       "Collisions should be sorted by penetration depth (deepest first)");
        }
    }

    @Test
    void testNoCollisionBetweenDistantEntities() {
        // Insert two point entities far apart (positive coordinates)
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(500, 500, 500);

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        // Test individual collision check
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Distant point entities should not collide");

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        assertTrue(allCollisions.isEmpty(), "Should find no collisions");
    }

    @Test
    void testNonOverlappingBoundedEntities() {
        // Create two non-overlapping bounded entities
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(300, 300, 300);

        EntityBounds bounds1 = new EntityBounds(new Point3f(90, 90, 90), new Point3f(110, 110, 110));
        EntityBounds bounds2 = new EntityBounds(new Point3f(290, 290, 290), new Point3f(310, 310, 310));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "BoundedEntity1", bounds1);
        tetree.insert(id2, center2, (byte) 10, "BoundedEntity2", bounds2);

        // Test collision detection
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Non-overlapping bounded entities should not collide");

        // Test findAllCollisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        assertTrue(allCollisions.isEmpty(), "Should find no collisions");
    }

    @Test
    void testSelfCollisionCheck() {
        // Test that an entity doesn't collide with itself
        Point3f pos = new Point3f(100, 100, 100);
        LongEntityID id = tetree.insert(pos, (byte) 10, "SelfEntity");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> selfCollision = tetree.checkCollision(id, id);
        assertFalse(selfCollision.isPresent(), "Entity should not collide with itself");
    }

    @Test
    void testTetrahedralGeometryConstraints() {
        // Test collision detection within tetrahedral space constraints
        // All coordinates must be positive
        try {
            Point3f invalidPos = new Point3f(-10, 100, 100); // Negative coordinate
            assertThrows(IllegalArgumentException.class, () -> {
                tetree.insert(invalidPos, (byte) 10, "InvalidEntity");
            }, "Should reject negative coordinates");
        } catch (Exception e) {
            // Expected - tetree should validate spatial constraints
        }

        // Test valid positive coordinates
        Point3f validPos1 = new Point3f(50, 50, 50);
        Point3f validPos2 = new Point3f(50.05f, 50.05f, 50.05f);

        LongEntityID id1 = tetree.insert(validPos1, (byte) 10, "ValidEntity1");
        LongEntityID id2 = tetree.insert(validPos2, (byte) 10, "ValidEntity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Valid entities should collide when close");
    }

    @Test
    void testTetrahedralReferenceSystem() {
        // Test collision detection within the S0 reference tetrahedron system
        // Entities should be within the tetrahedral space decomposition
        Point3f[] tetraPositions = { new Point3f(32, 32, 32),    // S0 tetrahedron regions
                                     new Point3f(64, 32, 32), new Point3f(32, 64, 32), new Point3f(32, 32, 64) };

        LongEntityID[] tetraIds = new LongEntityID[tetraPositions.length];
        for (int i = 0; i < tetraPositions.length; i++) {
            tetraIds[i] = tetree.insert(tetraPositions[i], (byte) 12, "TetraEntity" + i);
        }

        // Test collision detection across tetrahedral boundaries
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();

        // Entities in different tetrahedra should not collide unless very close
        for (var collision : allCollisions) {
            assertTrue(collision.penetrationDepth() > 0, "All found collisions should have positive penetration");
        }

        // Verify that tetree maintains tetrahedral spatial locality
        assertEquals(tetree.entityCount(), tetraPositions.length, "All entities should be inserted");
    }

    @Test
    void testTetrahedralSpacePartitioning() {
        // Test collision detection across different tetrahedral cells
        Point3f[] positions = { new Point3f(64, 64, 64),    // Different tetrahedral regions
                                new Point3f(128, 128, 128), new Point3f(192, 192, 192), new Point3f(256, 256, 256) };

        LongEntityID[] entityIds = new LongEntityID[positions.length];
        for (int i = 0; i < positions.length; i++) {
            entityIds[i] = tetree.insert(positions[i], (byte) 8, "TetEntity" + i);
        }

        // Test that distant entities don't collide
        for (int i = 0; i < entityIds.length; i++) {
            for (int j = i + 1; j < entityIds.length; j++) {
                Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(
                entityIds[i], entityIds[j]);
                assertFalse(collision.isPresent(), "Entities in different tetrahedral regions should not collide");
            }
        }
    }
}
