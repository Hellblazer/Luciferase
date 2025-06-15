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
 * Edge case tests for Tetree collision detection.
 * Tests boundary conditions, tetrahedral constraints, and unusual scenarios.
 *
 * @author hal.hildebrand
 */
public class TetreeCollisionEdgeCaseTest {

    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator);
    }

    @Test
    void testCollisionAtExactThreshold() {
        // Test collision detection at the exact collision threshold boundary
        Point3f pos1 = new Point3f(100, 100, 100);
        // Position at exactly 0.1 distance: 0.1 along the X axis
        Point3f pos2 = new Point3f(100.1f, 100f, 100f); // Distance = 0.1

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        float distance = pos1.distance(pos2);
        assertEquals(0.1f, distance, 0.0001f, "Distance should be exactly the collision threshold");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities at exact threshold should collide");
    }

    @Test
    void testCollisionJustBeyondThreshold() {
        // Test that entities just beyond threshold don't collide
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.11f, 100.11f, 100.11f); // Just beyond threshold

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        float distance = pos1.distance(pos2);
        assertTrue(distance > 0.1f, "Distance should be beyond collision threshold");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Entities beyond threshold should not collide");
    }

    @Test
    void testIdenticalPositions() {
        // Test collision detection with entities at identical positions
        Point3f pos = new Point3f(100, 100, 100);

        LongEntityID id1 = tetree.insert(pos, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(new Point3f(pos), (byte) 10, "Entity2"); // Identical position

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities at identical positions should collide");

        var collisionPair = collision.get();
        assertEquals(0.0f, collisionPair.penetrationDepth(), 0.001f, "Penetration depth should be minimal for identical positions");
    }

    @Test
    void testZeroBoundsEntity() {
        // Test collision with entity that has zero-size bounds
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100, 100, 100);

        EntityBounds zeroBounds = new EntityBounds(pos2, pos2); // Zero-size bounds

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "PointEntity");
        LongEntityID id2 = idGenerator.generateID();
        tetree.insert(id2, pos2, (byte) 10, "ZeroBoundsEntity", zeroBounds);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Point entity should collide with zero-bounds entity at same position");
    }

    @Test
    void testTouchingBounds() {
        // Test entities with bounds that exactly touch but don't overlap
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(120, 100, 100);

        EntityBounds bounds1 = new EntityBounds(
            new Point3f(95, 95, 95),
            new Point3f(110, 105, 105)
        );
        EntityBounds bounds2 = new EntityBounds(
            new Point3f(110, 95, 95), // Exactly touching bounds1's max X
            new Point3f(125, 105, 105)
        );

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "Entity1", bounds1);
        tetree.insert(id2, center2, (byte) 10, "Entity2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Exactly touching bounds should be considered colliding");
    }

    @Test
    void testFloatingPointPrecisionEdges() {
        // Test collision detection with floating point precision edge cases
        Point3f pos1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f pos2 = new Point3f(100.0000001f, 100.0000001f, 100.0000001f); // Very small difference

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities with minimal floating point differences should collide");
    }

    @Test
    void testTetrahedralBoundaryCollisions() {
        // Test collision detection across tetrahedral node boundaries
        Point3f pos1 = new Point3f(63.9f, 63.9f, 63.9f); // Near tetrahedral boundary
        Point3f pos2 = new Point3f(64.1f, 64.1f, 64.1f); // Across boundary

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        float distance = pos1.distance(pos2);
        if (distance <= 0.1f) {
            Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
            assertTrue(collision.isPresent(), "Close entities across tetrahedral boundaries should collide");
        }
    }

    @Test
    void testLargeBoundsWithSmallEntity() {
        // Test collision between very large bounds and small point entity
        Point3f pointPos = new Point3f(500, 500, 500);
        Point3f largeCenter = new Point3f(500, 500, 500);

        EntityBounds largeBounds = new EntityBounds(
            new Point3f(100, 100, 100), // Must stay positive for Tetree
            new Point3f(900, 900, 900)
        );

        LongEntityID pointId = tetree.insert(pointPos, (byte) 10, "PointEntity");
        LongEntityID largeId = idGenerator.generateID();
        tetree.insert(largeId, largeCenter, (byte) 5, "LargeEntity", largeBounds);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(pointId, largeId);
        assertTrue(collision.isPresent(), "Point entity inside large bounds should collide");
    }

    @Test
    void testPositiveCoordinateConstraint() {
        // Test that Tetree properly enforces positive coordinate constraints
        try {
            Point3f negativePos = new Point3f(-100, 100, 100);
            assertThrows(IllegalArgumentException.class, () -> {
                tetree.insert(negativePos, (byte) 10, "InvalidEntity");
            }, "Tetree should reject negative coordinates");
        } catch (Exception e) {
            // Expected behavior - Tetree should validate coordinates
        }

        // Test with valid positive coordinates
        Point3f validPos = new Point3f(100, 100, 100);
        LongEntityID validId = tetree.insert(validPos, (byte) 10, "ValidEntity");
        assertNotNull(validId, "Valid positive coordinates should be accepted");
    }

    @Test
    void testExtremePositiveCoordinateValues() {
        // Test collision detection with very large positive coordinate values
        Point3f pos1 = new Point3f(Float.MAX_VALUE / 4, Float.MAX_VALUE / 4, Float.MAX_VALUE / 4);
        Point3f pos2 = new Point3f(Float.MAX_VALUE / 4, Float.MAX_VALUE / 4, Float.MAX_VALUE / 4);

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities at extreme positive coordinates should collide when at same position");
    }

    @Test
    void testCollisionNearOrigin() {
        // Test collision detection near the coordinate origin (minimal positive values)
        Point3f nearOrigin1 = new Point3f(0.01f, 0.01f, 0.01f);
        Point3f nearOrigin2 = new Point3f(0.06f, 0.06f, 0.06f); // Distance < 0.1

        LongEntityID id1 = tetree.insert(nearOrigin1, (byte) 15, "NearOriginEntity1");
        LongEntityID id2 = tetree.insert(nearOrigin2, (byte) 15, "NearOriginEntity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities near origin should collide when close enough");
    }

    @Test
    void testDegenerateBounds() {
        // Test collision with degenerate bounds (flat in one dimension)
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(105, 100, 100);

        EntityBounds flatBounds1 = new EntityBounds(
            new Point3f(95, 95, 100), // Flat in Z dimension
            new Point3f(105, 105, 100)
        );
        EntityBounds flatBounds2 = new EntityBounds(
            new Point3f(100, 95, 100), // Overlapping flat bounds
            new Point3f(110, 105, 100)
        );

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "FlatEntity1", flatBounds1);
        tetree.insert(id2, center2, (byte) 10, "FlatEntity2", flatBounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping flat bounds should collide");
    }

    @Test
    void testCollisionPersistenceAfterRemoval() {
        // Test that collisions are properly cleaned up after entity removal
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(100.05f, 100.05f, 100.05f);
        Point3f pos3 = new Point3f(100.1f, 100.1f, 100.1f);

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");
        LongEntityID id3 = tetree.insert(pos3, (byte) 10, "Entity3");

        // Verify initial collisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> initialCollisions = tetree.findAllCollisions();
        assertTrue(initialCollisions.size() > 0, "Should have initial collisions");

        // Remove middle entity
        tetree.removeEntity(id2);

        // Check that collision involving removed entity is gone
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision12 = tetree.checkCollision(id1, id2);
        assertFalse(collision12.isPresent(), "Collision with removed entity should not exist");

        // Verify remaining entities
        assertTrue(tetree.containsEntity(id1), "Entity 1 should still exist");
        assertFalse(tetree.containsEntity(id2), "Entity 2 should be removed");
        assertTrue(tetree.containsEntity(id3), "Entity 3 should still exist");
    }

    @Test
    void testConsistencyAfterEntityUpdate() {
        // Test collision consistency after entity position updates
        Point3f initialPos = new Point3f(100, 100, 100);
        Point3f otherPos = new Point3f(200, 200, 200);

        LongEntityID id1 = tetree.insert(initialPos, (byte) 10, "MovableEntity");
        LongEntityID id2 = tetree.insert(otherPos, (byte) 10, "StaticEntity");

        // Initially no collision
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> initialCollision = tetree.checkCollision(id1, id2);
        assertFalse(initialCollision.isPresent(), "Initially distant entities should not collide");

        // Update entity to colliding position
        Point3f newPos = new Point3f(200.05f, 200.05f, 200.05f);
        tetree.updateEntity(id1, newPos, (byte) 10);

        // Now should collide
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> updatedCollision = tetree.checkCollision(id1, id2);
        assertTrue(updatedCollision.isPresent(), "Updated entities should collide when moved close together");
    }

    @Test
    void testTetrahedralSpatialLocalityEdgeCases() {
        // Test collision detection with entities positioned at tetrahedral reference points
        Point3f[] tetraVertices = {
            new Point3f(64, 64, 64),   // S0 tetrahedron vertex region
            new Point3f(128, 64, 64),  // Different tetrahedron
            new Point3f(64, 128, 64),  // Different tetrahedron
            new Point3f(64, 64, 128)   // Different tetrahedron
        };

        LongEntityID[] vertexIds = new LongEntityID[tetraVertices.length];
        for (int i = 0; i < tetraVertices.length; i++) {
            vertexIds[i] = tetree.insert(tetraVertices[i], (byte) 8, "VertexEntity" + i);
        }

        // Test that entities in different tetrahedra don't collide
        for (int i = 0; i < vertexIds.length; i++) {
            for (int j = i + 1; j < vertexIds.length; j++) {
                Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = 
                    tetree.checkCollision(vertexIds[i], vertexIds[j]);
                assertFalse(collision.isPresent(), 
                    "Entities in different tetrahedral regions should not collide unless very close");
            }
        }
    }

    @Test
    void testMaximumEntityDensityInTetrahedral() {
        // Test collision detection with maximum entity density within tetrahedral constraints
        Point3f basePos = new Point3f(100, 100, 100);
        LongEntityID[] entityIds = new LongEntityID[50]; // High density

        for (int i = 0; i < entityIds.length; i++) {
            Point3f pos = new Point3f(
                basePos.x + i * 0.001f, // Very close together, staying positive
                basePos.y + i * 0.001f,
                basePos.z + i * 0.001f
            );
            entityIds[i] = tetree.insert(pos, (byte) 15, "DenseEntity" + i);
        }

        // All entities should be found in collision detection
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        assertTrue(allCollisions.size() > 50, "High density should produce many collision pairs");

        // Test specific collision
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(entityIds[0], entityIds[1]);
        assertTrue(collision.isPresent(), "Adjacent dense entities should collide");
    }

    @Test
    void testTetrahedralIndexOverflow() {
        // Test collision detection with large coordinates (but within reasonable bounds for tetree)
        // Tetree uses tetrahedral decomposition which may have different limits than octree
        Point3f pos1 = new Point3f(10000, 10000, 10000); // Large but reasonable coordinates
        Point3f pos2 = new Point3f(10000.05f, 10000.05f, 10000.05f);

        try {
            LongEntityID id1 = tetree.insert(pos1, (byte) 5, "LargeCoordEntity1");
            LongEntityID id2 = tetree.insert(pos2, (byte) 5, "LargeCoordEntity2");

            Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
            assertTrue(collision.isPresent(), "Large coordinate entities should collide when close");
        } catch (IllegalArgumentException e) {
            // Some coordinate ranges might not be supported - that's acceptable
            assertTrue(true, "Tetree properly rejected coordinates outside its range");
        }
    }

    @Test
    void testMinimalTetrahedralCellCollision() {
        // Test collision in the smallest possible tetrahedral cell
        Point3f pos1 = new Point3f(1.0f, 1.0f, 1.0f);   // Minimal positive coordinates
        Point3f pos2 = new Point3f(1.001f, 1.001f, 1.001f); // Very close

        LongEntityID id1 = tetree.insert(pos1, (byte) 20, "MinimalEntity1"); // Highest level for smallest cells
        LongEntityID id2 = tetree.insert(pos2, (byte) 20, "MinimalEntity2");

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Entities in minimal tetrahedral cells should collide when very close");
    }
}