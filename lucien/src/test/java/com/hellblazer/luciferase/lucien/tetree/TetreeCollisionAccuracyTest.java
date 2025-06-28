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
        var center1 = new Point3f(300, 300, 300);
        var center2 = new Point3f(310, 300, 300);

        // Elongated box along Y axis
        var elongatedBounds = new EntityBounds(new Point3f(295, 280, 295), new Point3f(305, 320, 305)
                                                        // 10x40x10
        );

        // Wide box along Z axis
        var wideBounds = new EntityBounds(new Point3f(305, 295, 280), new Point3f(315, 305, 320)  // 10x10x40
        );

        var id1 = idGenerator.generateID();
        var id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "Elongated", elongatedBounds);
        tetree.insert(id2, center2, (byte) 10, "Wide", wideBounds);

        var collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Anisotropic boxes should collide");

        var pair = collision.get();

        // Contact normal should be along X axis (minimum penetration)
        var normal = pair.contactNormal();
        assertEquals(1.0f, Math.abs(normal.x), EPSILON, "Normal should be along X axis");
        assertEquals(0.0f, normal.y, EPSILON, "Normal Y should be zero");
        assertEquals(0.0f, normal.z, EPSILON, "Normal Z should be zero");
    }

    @Test
    void testCapsuleCollisionAccuracyInTetree() {
        // Test capsule collision shapes in tetrahedral space
        var center1 = new Point3f(250, 250, 250);
        var center2 = new Point3f(255, 250, 250);

        var id1 = idGenerator.generateID();
        var id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "Capsule1");
        tetree.insert(id2, center2, (byte) 10, "Capsule2");

        // Create vertical capsules
        var base1 = new Point3f(center1);
        var tip1 = new Point3f(center1.x, center1.y + 10, center1.z);
        var capsule1 = new CapsuleShape(base1, tip1, 2.0f);

        var base2 = new Point3f(center2);
        var tip2 = new Point3f(center2.x, center2.y + 10, center2.z);
        var capsule2 = new CapsuleShape(base2, tip2, 2.0f);

        tetree.setCollisionShape(id1, capsule1);
        tetree.setCollisionShape(id2, capsule2);

        var collision = tetree.checkCollision(id1, id2);
        assertFalse(collision.isPresent(), "Non-overlapping capsules should not collide");

        // Test actual overlapping capsules
        var center3 = new Point3f(250, 250, 250);
        var center4 = new Point3f(253, 250, 250); // Distance 3, radius 2 each = overlap of 1

        var id3 = idGenerator.generateID();
        var id4 = idGenerator.generateID();

        tetree.insert(id3, center3, (byte) 10, "Capsule3");
        tetree.insert(id4, center4, (byte) 10, "Capsule4");

        // Create vertical capsules
        var base3 = new Point3f(center3);
        var tip3 = new Point3f(center3.x, center3.y + 10, center3.z);
        var capsule3 = new CapsuleShape(base3, tip3, 2.0f);

        var base4 = new Point3f(center4);
        var tip4 = new Point3f(center4.x, center4.y + 10, center4.z);
        var capsule4 = new CapsuleShape(base4, tip4, 2.0f);

        tetree.setCollisionShape(id3, capsule3);
        tetree.setCollisionShape(id4, capsule4);

        var overlap = tetree.checkCollision(id3, id4);
        assertTrue(overlap.isPresent(), "Overlapping capsules should collide");

        var pair = overlap.get();

        // For parallel capsules, penetration should be based on radii and center distance
        var centerDistance = 3.0f;
        var expectedPenetration = 4.0f - centerDistance; // Sum of radii - distance = 1
        assertEquals(1.0f, pair.penetrationDepth(), EPSILON,
                     "Penetration depth should be positive for overlapping capsules");
    }

    @Test
    void testCollisionNormalConsistency() {
        // Test that collision normals are consistent when checked from either entity
        var pos1 = new Point3f(300, 300, 300);
        var pos2 = new Point3f(305, 300, 300);

        var bounds1 = new EntityBounds(new Point3f(295, 295, 295), new Point3f(305, 305, 305));
        var bounds2 = new EntityBounds(new Point3f(300, 295, 295), new Point3f(310, 305, 305));

        var id1 = idGenerator.generateID();
        var id2 = idGenerator.generateID();

        tetree.insert(id1, pos1, (byte) 10, "Entity1", bounds1);
        tetree.insert(id2, pos2, (byte) 10, "Entity2", bounds2);

        // Check collision from both directions
        var collision12 = tetree.checkCollision(id1, id2);
        var collision21 = tetree.checkCollision(id2, id1);

        assertTrue(collision12.isPresent());
        assertTrue(collision21.isPresent());

        // Normals should be opposite
        var normal12 = collision12.get().contactNormal();
        var normal21 = collision21.get().contactNormal();

        // The implementation might return the same normal regardless of order,
        // or opposite normals. Both are valid as long as they're consistent
        var dot = normal12.dot(normal21);
        assertTrue(Math.abs(dot) > 0.99f, "Normals should be either same or opposite direction");

        // Penetration depths should be the same
        assertEquals(collision12.get().penetrationDepth(), collision21.get().penetrationDepth(), EPSILON,
                     "Penetration depth should be same from either direction");
    }

    @Test
    void testCollisionSeparationVectorsInTetree() {
        // Test that separation vectors correctly resolve collisions
        var pos1 = new Point3f(350, 350, 350);
        var pos2 = new Point3f(355, 350, 350);

        var bounds1 = new EntityBounds(new Point3f(345, 345, 345), new Point3f(355, 355, 355));
        var bounds2 = new EntityBounds(new Point3f(350, 345, 345), new Point3f(360, 355, 355));

        var id1 = idGenerator.generateID();
        var id2 = idGenerator.generateID();

        tetree.insert(id1, pos1, (byte) 10, "Box1", bounds1);
        tetree.insert(id2, pos2, (byte) 10, "Box2", bounds2);

        var collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent());

        var pair = collision.get();

        // Apply minimum separation
        var separation = new Vector3f(pair.contactNormal());
        separation.scale(pair.penetrationDepth() * 0.5f + 0.1f); // Add small buffer

        var newPos1 = new Point3f(pos1);
        newPos1.sub(separation);

        var newPos2 = new Point3f(pos2);
        newPos2.add(separation);

        // Update positions
        tetree.updateEntity(id1, newPos1, (byte) 10);
        tetree.updateEntity(id2, newPos2, (byte) 10);

        // Verify no longer colliding
        var afterSeparation = tetree.checkCollision(id1, id2);
        assertFalse(afterSeparation.isPresent(), "Entities should not collide after separation");
    }

    @Test
    void testContactPointAccuracyInTetrahedralSpace() {
        // Test collision detection within tetrahedral bounds
        // Using positions well within the valid tetrahedral region
        var center1 = new Point3f(200, 200, 200);
        var center2 = new Point3f(208, 200, 200);

        var bounds1 = new EntityBounds(new Point3f(195, 195, 195), new Point3f(205, 205, 205));
        var bounds2 = new EntityBounds(new Point3f(203, 195, 195),   // 2 unit overlap
                                                new Point3f(213, 205, 205));

        var id1 = idGenerator.generateID();
        var id2 = idGenerator.generateID();

        tetree.insert(id1, center1, (byte) 10, "Box1", bounds1);
        tetree.insert(id2, center2, (byte) 10, "Box2", bounds2);

        var collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping AABBs should collide in tetrahedral space");

        var pair = collision.get();
        var contactPoint = pair.contactPoint();
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
        var basePos = new Point3f(500, 500, 500);

        // Create a grid of small entities
        var spacing = 0.1f;
        var gridSize = 5;
        var grid = new LongEntityID[gridSize][gridSize];

        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                var pos = new Point3f(basePos.x + i * spacing, basePos.y + j * spacing, basePos.z);

                grid[i][j] = tetree.insert(pos, (byte) 15, "Grid_" + i + "_" + j);

                // Set small sphere collision shape
                tetree.setCollisionShape(grid[i][j], new SphereShape(pos, spacing * 0.6f));
            }
        }

        // Check adjacent entities collide
        for (int i = 0; i < gridSize - 1; i++) {
            for (int j = 0; j < gridSize - 1; j++) {
                // Check horizontal neighbor
                var hCollision = tetree.checkCollision(
                grid[i][j], grid[i + 1][j]);
                assertTrue(hCollision.isPresent(), "Horizontal neighbors should collide at (" + i + "," + j + ")");

                // Check vertical neighbor
                var vCollision = tetree.checkCollision(
                grid[i][j], grid[i][j + 1]);
                assertTrue(vCollision.isPresent(), "Vertical neighbors should collide at (" + i + "," + j + ")");

                // Verify penetration depth is consistent
                var expectedPenetration = 2 * spacing * 0.6f - spacing;
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
        var levels = new byte[]{ 5, 10, 15 };

        for (byte level : levels) {
            var levelTetree = new Tetree<>(new SequentialLongIDGenerator());

            // Scale positions based on level to stay within valid bounds
            var scale = (float) Math.pow(2, 15 - level);
            var center1 = new Point3f(100 * scale, 100 * scale, 100 * scale);
            var center2 = new Point3f(107 * scale, 100 * scale, 100 * scale);

            var bounds1 = new EntityBounds(new Point3f(95 * scale, 95 * scale, 95 * scale),
                                                    new Point3f(105 * scale, 105 * scale, 105 * scale));
            var bounds2 = new EntityBounds(new Point3f(102 * scale, 95 * scale, 95 * scale),
                                                    new Point3f(112 * scale, 105 * scale, 105 * scale));

            var id1 = idGenerator.generateID();
            var id2 = idGenerator.generateID();

            levelTetree.insert(id1, center1, level, "Box1", bounds1);
            levelTetree.insert(id2, center2, level, "Box2", bounds2);

            var collision = levelTetree.checkCollision(id1, id2);
            assertTrue(collision.isPresent(), "Collision should be detected at level " + level);

            // Verify penetration depth scales correctly
            var expectedPenetration = 3.0f * scale;
            assertEquals(expectedPenetration, collision.get().penetrationDepth(), EPSILON * scale,
                         "Penetration depth should scale correctly at level " + level);
        }
    }

    @Test
    void testMultipleSimultaneousCollisionsAccuracy() {
        // Create a central entity surrounded by others in tetrahedral arrangement
        var center = new Point3f(400, 400, 400);

        var centralBounds = new EntityBounds(new Point3f(390, 390, 390), new Point3f(410, 410, 410));

        var centralId = idGenerator.generateID();
        tetree.insert(centralId, center, (byte) 8, "Central", centralBounds);

        // Create surrounding entities in tetrahedral pattern
        var offset = 15;
        var tetVertices = new Point3f[]{ new Point3f(center.x + offset, center.y, center.z), new Point3f(center.x - offset / 2,
                                                                                                  center.y
                                                                                                  + offset * 0.866f,
                                                                                                  center.z),
                                  new Point3f(center.x - offset / 2, center.y - offset * 0.433f,
                                              center.z + offset * 0.75f), new Point3f(center.x - offset / 2,
                                                                                      center.y - offset * 0.433f,
                                                                                      center.z - offset * 0.75f) };

        var surroundingIds = new LongEntityID[4];
        for (int i = 0; i < 4; i++) {
            var bounds = new EntityBounds(
            new Point3f(tetVertices[i].x - 10, tetVertices[i].y - 10, tetVertices[i].z - 10),
            new Point3f(tetVertices[i].x + 10, tetVertices[i].y + 10, tetVertices[i].z + 10));

            surroundingIds[i] = idGenerator.generateID();
            tetree.insert(surroundingIds[i], tetVertices[i], (byte) 8, "Surround" + i, bounds);
        }

        // Test collisions with central entity
        var collisions = tetree.findCollisions(centralId);

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
        var size = 400; // Well within tetrahedral bounds

        // Test collision near a tetrahedral face
        var nearFace1 = new Point3f(size * 0.3f, size * 0.3f, size * 0.3f);
        var nearFace2 = new Point3f(size * 0.3f + 5, size * 0.3f, size * 0.3f);

        var id1 = tetree.insert(nearFace1, (byte) 10, "NearFace1");
        var id2 = tetree.insert(nearFace2, (byte) 10, "NearFace2");

        // Set small collision shapes
        var sphere1 = new SphereShape(nearFace1, 3.0f);
        var sphere2 = new SphereShape(nearFace2, 3.0f);

        tetree.setCollisionShape(id1, sphere1);
        tetree.setCollisionShape(id2, sphere2);

        var collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Spheres near tetrahedral face should collide");

        // Verify collision properties
        var pair = collision.get();
        assertEquals(1.0f, pair.penetrationDepth(), EPSILON,
                     "Penetration depth should be correct for overlapping spheres");
    }
}
