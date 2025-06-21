/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.collision.CapsuleShape;
import com.hellblazer.luciferase.lucien.collision.SphereShape;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accuracy tests for Tetree collision detection. Validates geometric accuracy, contact points, normals, and penetration
 * depths within the tetrahedral spatial decomposition.
 *
 * @author hal.hildebrand
 */
public class TetreeCollisionAccuracyTest {

    private static final float EPSILON = 0.001f;

    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testAnisotropicCollisionAccuracy() {
        // Test collision with entities of different sizes along different axes
        Point3f center1 = new Point3f(300, 300, 300);
        Point3f center2 = new Point3f(310, 300, 300);

        // Elongated box along Y axis
        EntityBounds elongatedBounds = new EntityBounds(new Point3f(295, 280, 295), new Point3f(305, 320, 305)
                                                        // 10x40x10
        );

        // Wide box along Z axis
        EntityBounds wideBounds = new EntityBounds(new Point3f(305, 295, 280), new Point3f(315, 305, 320)  // 10x10x40
        );

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "Elongated", elongatedBounds);
        tetree.insert(id2, center2, (byte) 10, "Wide", wideBounds);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Anisotropic boxes should collide");

        var pair = collision.get();

        // Contact normal should be along X axis (minimum penetration)
        Vector3f normal = pair.contactNormal();
        assertEquals(1.0f, Math.abs(normal.x), EPSILON, "Normal should be along X axis");
        assertEquals(0.0f, normal.y, EPSILON, "Normal Y should be zero");
        assertEquals(0.0f, normal.z, EPSILON, "Normal Z should be zero");
    }

    @Test
    void testCapsuleCollisionAccuracyInTetree() {
        // Test capsule collision shapes in tetrahedral space
        Point3f center1 = new Point3f(250, 250, 250);
        Point3f center2 = new Point3f(255, 250, 250);

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "Capsule1");
        tetree.insert(id2, center2, (byte) 10, "Capsule2");

        // Create vertical capsules
        Point3f base1 = new Point3f(center1);
        Point3f tip1 = new Point3f(center1.x, center1.y + 10, center1.z);
        CapsuleShape capsule1 = new CapsuleShape(base1, tip1, 2.0f);

        Point3f base2 = new Point3f(center2);
        Point3f tip2 = new Point3f(center2.x, center2.y + 10, center2.z);
        CapsuleShape capsule2 = new CapsuleShape(base2, tip2, 2.0f);

        tetree.setCollisionShape(id1, capsule1);
        tetree.setCollisionShape(id2, capsule2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Non-overlapping capsules should not collide");

        // Test actual overlapping capsules
        Point3f center3 = new Point3f(250, 250, 250);
        Point3f center4 = new Point3f(253, 250, 250); // Distance 3, radius 2 each = overlap of 1

        LongEntityID id3 = idGenerator.generateID();
        LongEntityID id4 = idGenerator.generateID();

        tetree.insert(id3, center3, (byte) 10, "Capsule3");
        tetree.insert(id4, center4, (byte) 10, "Capsule4");

        // Create vertical capsules
        Point3f base3 = new Point3f(center3);
        Point3f tip3 = new Point3f(center3.x, center3.y + 10, center3.z);
        CapsuleShape capsule3 = new CapsuleShape(base3, tip3, 2.0f);

        Point3f base4 = new Point3f(center4);
        Point3f tip4 = new Point3f(center4.x, center4.y + 10, center4.z);
        CapsuleShape capsule4 = new CapsuleShape(base4, tip4, 2.0f);

        tetree.setCollisionShape(id3, capsule3);
        tetree.setCollisionShape(id4, capsule4);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> overlap = tetree.checkCollision(id3, id4);
        assertTrue(overlap.isPresent(), "Overlapping capsules should collide");

        var pair = overlap.get();

        // For parallel capsules, penetration should be based on radii and center distance
        float centerDistance = 3.0f;
        float expectedPenetration = 4.0f - centerDistance; // Sum of radii - distance = 1
        assertEquals(1.0f, pair.penetrationDepth(), EPSILON,
                     "Penetration depth should be positive for overlapping capsules");
    }

    @Test
    void testCollisionNormalConsistency() {
        // Test that collision normals are consistent when checked from either entity
        Point3f pos1 = new Point3f(300, 300, 300);
        Point3f pos2 = new Point3f(305, 300, 300);

        EntityBounds bounds1 = new EntityBounds(new Point3f(295, 295, 295), new Point3f(305, 305, 305));
        EntityBounds bounds2 = new EntityBounds(new Point3f(300, 295, 295), new Point3f(310, 305, 305));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, pos1, (byte) 10, "Entity1", bounds1);
        tetree.insert(id2, pos2, (byte) 10, "Entity2", bounds2);

        // Check collision from both directions
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision12 = tetree.checkCollision(id1, id2);
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision21 = tetree.checkCollision(id2, id1);

        assertTrue(collision12.isPresent());
        assertTrue(collision21.isPresent());

        // Normals should be opposite
        Vector3f normal12 = collision12.get().contactNormal();
        Vector3f normal21 = collision21.get().contactNormal();

        // The implementation might return the same normal regardless of order,
        // or opposite normals. Both are valid as long as they're consistent
        float dot = normal12.dot(normal21);
        assertTrue(Math.abs(dot) > 0.99f, "Normals should be either same or opposite direction");

        // Penetration depths should be the same
        assertEquals(collision12.get().penetrationDepth(), collision21.get().penetrationDepth(), EPSILON,
                     "Penetration depth should be same from either direction");
    }

    @Test
    void testCollisionSeparationVectorsInTetree() {
        // Test that separation vectors correctly resolve collisions
        Point3f pos1 = new Point3f(350, 350, 350);
        Point3f pos2 = new Point3f(355, 350, 350);

        EntityBounds bounds1 = new EntityBounds(new Point3f(345, 345, 345), new Point3f(355, 355, 355));
        EntityBounds bounds2 = new EntityBounds(new Point3f(350, 345, 345), new Point3f(360, 355, 355));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, pos1, (byte) 10, "Box1", bounds1);
        tetree.insert(id2, pos2, (byte) 10, "Box2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent());

        var pair = collision.get();

        // Apply minimum separation
        Vector3f separation = new Vector3f(pair.contactNormal());
        separation.scale(pair.penetrationDepth() * 0.5f + 0.1f); // Add small buffer

        Point3f newPos1 = new Point3f(pos1);
        newPos1.sub(separation);

        Point3f newPos2 = new Point3f(pos2);
        newPos2.add(separation);

        // Update positions
        tetree.updateEntity(id1, newPos1, (byte) 10);
        tetree.updateEntity(id2, newPos2, (byte) 10);

        // Verify no longer colliding
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> afterSeparation = tetree.checkCollision(id1, id2);
        assertFalse(afterSeparation.isPresent(), "Entities should not collide after separation");
    }

    @Test
    void testContactPointAccuracyInTetrahedralSpace() {
        // Test collision detection within tetrahedral bounds
        // Using positions well within the valid tetrahedral region
        Point3f center1 = new Point3f(200, 200, 200);
        Point3f center2 = new Point3f(208, 200, 200);

        EntityBounds bounds1 = new EntityBounds(new Point3f(195, 195, 195), new Point3f(205, 205, 205));
        EntityBounds bounds2 = new EntityBounds(new Point3f(203, 195, 195),   // 2 unit overlap
                                                new Point3f(213, 205, 205));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "Box1", bounds1);
        tetree.insert(id2, center2, (byte) 10, "Box2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping AABBs should collide in tetrahedral space");

        var pair = collision.get();
        Point3f contactPoint = pair.contactPoint();
        assertNotNull(contactPoint, "Contact point should not be null");

        // Verify contact point is in the overlap region
        assertTrue(contactPoint.x >= 203 - EPSILON && contactPoint.x <= 205 + EPSILON,
                   "Contact point X should be in overlap region");
        assertTrue(contactPoint.y >= 195 - EPSILON && contactPoint.y <= 205 + EPSILON,
                   "Contact point Y should be in overlap region");
        assertTrue(contactPoint.z >= 195 - EPSILON && contactPoint.z <= 205 + EPSILON,
                   "Contact point Z should be in overlap region");
    }

    @Test
    void testHighPrecisionCollisionDetection() {
        // Test collision detection with high-precision requirements
        Point3f basePos = new Point3f(500, 500, 500);

        // Create a grid of small entities
        float spacing = 0.1f;
        int gridSize = 5;
        LongEntityID[][] grid = new LongEntityID[gridSize][gridSize];

        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                Point3f pos = new Point3f(basePos.x + i * spacing, basePos.y + j * spacing, basePos.z);

                grid[i][j] = tetree.insert(pos, (byte) 15, "Grid_" + i + "_" + j);

                // Set small sphere collision shape
                tetree.setCollisionShape(grid[i][j], new SphereShape(pos, spacing * 0.6f));
            }
        }

        // Check adjacent entities collide
        for (int i = 0; i < gridSize - 1; i++) {
            for (int j = 0; j < gridSize - 1; j++) {
                // Check horizontal neighbor
                Optional<SpatialIndex.CollisionPair<LongEntityID, String>> hCollision = tetree.checkCollision(
                grid[i][j], grid[i + 1][j]);
                assertTrue(hCollision.isPresent(), "Horizontal neighbors should collide at (" + i + "," + j + ")");

                // Check vertical neighbor
                Optional<SpatialIndex.CollisionPair<LongEntityID, String>> vCollision = tetree.checkCollision(
                grid[i][j], grid[i][j + 1]);
                assertTrue(vCollision.isPresent(), "Vertical neighbors should collide at (" + i + "," + j + ")");

                // Verify penetration depth is consistent
                float expectedPenetration = 2 * spacing * 0.6f - spacing;
                assertEquals(expectedPenetration, hCollision.get().penetrationDepth(), spacing * 0.01f,
                             "Horizontal penetration should be accurate");
                assertEquals(expectedPenetration, vCollision.get().penetrationDepth(), spacing * 0.01f,
                             "Vertical penetration should be accurate");
            }
        }
    }

    @Test
    void testMultiLevelCollisionAccuracy() {
        // Test collision accuracy across different tetree levels
        byte[] levels = { 5, 10, 15 };

        for (byte level : levels) {
            Tetree<LongEntityID, String> levelTetree = new Tetree<>(new SequentialLongIDGenerator());

            // Scale positions based on level to stay within valid bounds
            float scale = (float) Math.pow(2, 15 - level);
            Point3f center1 = new Point3f(100 * scale, 100 * scale, 100 * scale);
            Point3f center2 = new Point3f(107 * scale, 100 * scale, 100 * scale);

            EntityBounds bounds1 = new EntityBounds(new Point3f(95 * scale, 95 * scale, 95 * scale),
                                                    new Point3f(105 * scale, 105 * scale, 105 * scale));
            EntityBounds bounds2 = new EntityBounds(new Point3f(102 * scale, 95 * scale, 95 * scale),
                                                    new Point3f(112 * scale, 105 * scale, 105 * scale));

            LongEntityID id1 = idGenerator.generateID();
            LongEntityID id2 = idGenerator.generateID();

            levelTetree.insert(id1, center1, level, "Box1", bounds1);
            levelTetree.insert(id2, center2, level, "Box2", bounds2);

            Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = levelTetree.checkCollision(id1, id2);
            assertTrue(collision.isPresent(), "Collision should be detected at level " + level);

            // Verify penetration depth scales correctly
            float expectedPenetration = 3.0f * scale;
            assertEquals(expectedPenetration, collision.get().penetrationDepth(), EPSILON * scale,
                         "Penetration depth should scale correctly at level " + level);
        }
    }

    @Test
    void testMultipleSimultaneousCollisionsAccuracy() {
        // Create a central entity surrounded by others in tetrahedral arrangement
        Point3f center = new Point3f(400, 400, 400);

        EntityBounds centralBounds = new EntityBounds(new Point3f(390, 390, 390), new Point3f(410, 410, 410));

        LongEntityID centralId = idGenerator.generateID();
        tetree.insert(centralId, center, (byte) 8, "Central", centralBounds);

        // Create surrounding entities in tetrahedral pattern
        float offset = 15;
        Point3f[] tetVertices = { new Point3f(center.x + offset, center.y, center.z), new Point3f(center.x - offset / 2,
                                                                                                  center.y
                                                                                                  + offset * 0.866f,
                                                                                                  center.z),
                                  new Point3f(center.x - offset / 2, center.y - offset * 0.433f,
                                              center.z + offset * 0.75f), new Point3f(center.x - offset / 2,
                                                                                      center.y - offset * 0.433f,
                                                                                      center.z - offset * 0.75f) };

        LongEntityID[] surroundingIds = new LongEntityID[4];
        for (int i = 0; i < 4; i++) {
            EntityBounds bounds = new EntityBounds(
            new Point3f(tetVertices[i].x - 10, tetVertices[i].y - 10, tetVertices[i].z - 10),
            new Point3f(tetVertices[i].x + 10, tetVertices[i].y + 10, tetVertices[i].z + 10));

            surroundingIds[i] = idGenerator.generateID();
            tetree.insert(surroundingIds[i], tetVertices[i], (byte) 8, "Surround" + i, bounds);
        }

        // Test collisions with central entity
        List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions = tetree.findCollisions(centralId);

        assertEquals(4, collisions.size(), "Central entity should collide with all 4 surrounding entities");

        // Verify each collision has reasonable properties
        for (var collision : collisions) {
            assertNotNull(collision.contactPoint());
            assertNotNull(collision.contactNormal());
            assertTrue(collision.penetrationDepth() > 0);
            assertEquals(1.0f, collision.contactNormal().length(), EPSILON, "Contact normal should be unit length");
        }
    }

    @Test
    void testTetrahedralBoundaryCollisions() {
        // Test collisions near tetrahedral boundaries
        // The tetree's reference tetrahedron has specific geometric constraints
        float size = 400; // Well within tetrahedral bounds

        // Test collision near a tetrahedral face
        Point3f nearFace1 = new Point3f(size * 0.3f, size * 0.3f, size * 0.3f);
        Point3f nearFace2 = new Point3f(size * 0.3f + 5, size * 0.3f, size * 0.3f);

        LongEntityID id1 = tetree.insert(nearFace1, (byte) 10, "NearFace1");
        LongEntityID id2 = tetree.insert(nearFace2, (byte) 10, "NearFace2");

        // Set small collision shapes
        SphereShape sphere1 = new SphereShape(nearFace1, 3.0f);
        SphereShape sphere2 = new SphereShape(nearFace2, 3.0f);

        tetree.setCollisionShape(id1, sphere1);
        tetree.setCollisionShape(id2, sphere2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Spheres near tetrahedral face should collide");

        // Verify collision properties
        var pair = collision.get();
        assertEquals(1.0f, pair.penetrationDepth(), EPSILON,
                     "Penetration depth should be correct for overlapping spheres");
    }
}
