/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.collision.*;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for collision shapes with Tetree spatial index.
 * Ensures feature parity with Octree collision shape support.
 *
 * @author hal.hildebrand
 */
public class TetreeCollisionShapeIntegrationTest {

    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator);
    }

    @Test
    void testSetGetCollisionShape() {
        // Insert entity with default AABB
        var pos = new Point3f(100, 100, 100);
        var bounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));
        var id = idGenerator.generateID();
        tetree.insert(id, pos, (byte) 10, "Entity", bounds);

        // Set custom sphere shape
        var sphereShape = new SphereShape(pos, 8.0f);
        tetree.setCollisionShape(id, sphereShape);

        // Verify shape is stored
        var retrievedShape = tetree.getCollisionShape(id);
        assertNotNull(retrievedShape, "Should retrieve stored collision shape");
        assertTrue(retrievedShape instanceof SphereShape, "Should be a SphereShape");
        assertEquals(8.0f, ((SphereShape) retrievedShape).getRadius(), 0.001f);

        // Update to different shape
        var boxShape = new BoxShape(pos, new Vector3f(10, 10, 10));
        tetree.setCollisionShape(id, boxShape);

        var newShape = tetree.getCollisionShape(id);
        assertTrue(newShape instanceof BoxShape, "Should be updated to BoxShape");

        // Test null shape (remove custom shape)
        tetree.setCollisionShape(id, null);
        assertNull(tetree.getCollisionShape(id), "Should return null after clearing shape");
    }

    @Test
    void testCollisionDetectionWithCustomShapes() {
        // Create two entities with sphere collision shapes
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(110, 100, 100);

        var id1 = tetree.insert(pos1, (byte) 10, "Sphere1");
        var id2 = tetree.insert(pos2, (byte) 10, "Sphere2");

        // Set sphere shapes with 6 unit radius (should overlap)
        tetree.setCollisionShape(id1, new SphereShape(pos1, 6.0f));
        tetree.setCollisionShape(id2, new SphereShape(pos2, 6.0f));

        // Check collision
        var collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Spheres should collide");
        assertEquals(2.0f, collision.get().penetrationDepth(), 0.1f, "Should have 2 unit penetration");

        // Move sphere2 further away
        var newPos2 = new Point3f(115, 100, 100);
        tetree.updateEntity(id2, newPos2, (byte) 10);
        tetree.setCollisionShape(id2, new SphereShape(newPos2, 6.0f));

        var collision2 = tetree.checkCollision(id1, id2);
        assertFalse(collision2.isPresent(), "Spheres should no longer collide");
    }

    @Test
    void testMixedShapeCollisions() {
        // Create entities with different shape types
        var spherePos = new Point3f(100, 100, 100);
        var boxPos = new Point3f(108, 100, 100);
        var capsulePos = new Point3f(100, 108, 100);

        var sphereId = tetree.insert(spherePos, (byte) 10, "Sphere");
        var boxId = tetree.insert(boxPos, (byte) 10, "Box");
        var capsuleId = tetree.insert(capsulePos, (byte) 10, "Capsule");

        // Set different collision shapes
        tetree.setCollisionShape(sphereId, new SphereShape(spherePos, 5.0f));
        tetree.setCollisionShape(boxId, new BoxShape(boxPos, new Vector3f(5, 5, 5)));
        tetree.setCollisionShape(capsuleId, new CapsuleShape(capsulePos, 10.0f, 3.0f));

        // Test sphere-box collision
        var sphereBoxCollision = tetree.checkCollision(sphereId, boxId);
        assertTrue(sphereBoxCollision.isPresent(), "Sphere and box should collide");

        // Test sphere-capsule collision
        var sphereCapsuleCollision = tetree.checkCollision(sphereId, capsuleId);
        assertTrue(sphereCapsuleCollision.isPresent(), "Sphere and capsule should collide");

        // Find all collisions
        var allCollisions = tetree.findAllCollisions();
        assertTrue(allCollisions.size() >= 2, "Should find at least 2 collision pairs");
    }

    @Test
    void testOrientedBoxCollisions() {
        // Create two oriented boxes at different angles
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(107, 100, 100);

        var id1 = tetree.insert(pos1, (byte) 10, "OBB1");
        var id2 = tetree.insert(pos2, (byte) 10, "OBB2");

        // Create rotated boxes
        var rotation1 = new Matrix3f();
        rotation1.rotY((float) Math.PI / 6); // 30 degrees
        var obb1 = new OrientedBoxShape(pos1, new Vector3f(5, 5, 5), rotation1);

        var rotation2 = new Matrix3f();
        rotation2.rotY((float) -Math.PI / 6); // -30 degrees
        var obb2 = new OrientedBoxShape(pos2, new Vector3f(5, 5, 5), rotation2);

        tetree.setCollisionShape(id1, obb1);
        tetree.setCollisionShape(id2, obb2);

        // Check collision
        var collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Oriented boxes should collide");
        assertTrue(collision.get().penetrationDepth() > 0, "Should have positive penetration");
    }

    @Test
    void testCapsuleCollisions() {
        // Test capsules in different orientations
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(110, 100, 100);
        var pos3 = new Point3f(100, 100, 110);

        var id1 = tetree.insert(pos1, (byte) 10, "CapsuleY");
        var id2 = tetree.insert(pos2, (byte) 10, "CapsuleX");
        var id3 = tetree.insert(pos3, (byte) 10, "CapsuleZ");

        // Create capsules along different axes
        tetree.setCollisionShape(id1, new CapsuleShape(pos1, 10.0f, 3.0f)); // vertical
        tetree.setCollisionShape(id2, new CapsuleShape(new Point3f(pos2.x - 5, pos2.y, pos2.z), new Point3f(pos2.x + 5, pos2.y, pos2.z), 3.0f)); // horizontal X
        tetree.setCollisionShape(id3, new CapsuleShape(new Point3f(pos3.x, pos3.y, pos3.z - 5), new Point3f(pos3.x, pos3.y, pos3.z + 5), 3.0f)); // horizontal Z

        // Check collisions
        var collision12 = tetree.checkCollision(id1, id2);
        assertTrue(collision12.isPresent(), "Y and X capsules should collide");

        var collision13 = tetree.checkCollision(id1, id3);
        assertTrue(collision13.isPresent(), "Y and Z capsules should collide");

        var collision23 = tetree.checkCollision(id2, id3);
        assertFalse(collision23.isPresent(), "X and Z capsules should not collide at these positions");
    }

    @Test
    void testCollisionShapeUpdate() {
        // Test that collision shapes are properly updated when entities move
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(120, 100, 100);

        var id1 = tetree.insert(pos1, (byte) 10, "MovingSphere");
        var id2 = tetree.insert(pos2, (byte) 10, "StaticSphere");

        tetree.setCollisionShape(id1, new SphereShape(pos1, 8.0f));
        tetree.setCollisionShape(id2, new SphereShape(pos2, 8.0f));

        // Initially no collision
        assertFalse(tetree.checkCollision(id1, id2).isPresent(), "Should not collide initially");

        // Move entity1 closer
        var newPos1 = new Point3f(110, 100, 100);
        tetree.updateEntity(id1, newPos1, (byte) 10);
        
        // Update collision shape position
        tetree.setCollisionShape(id1, new SphereShape(newPos1, 8.0f));

        // Now should collide
        assertTrue(tetree.checkCollision(id1, id2).isPresent(), "Should collide after movement");
    }

    @Test
    void testFindCollisionsWithCustomShapes() {
        // Create cluster of entities with various shapes
        var basePos = new Point3f(100, 100, 100);
        
        // Create entities in a small area
        var id1 = tetree.insert(new Point3f(100, 100, 100), (byte) 10, "Sphere1");
        var id2 = tetree.insert(new Point3f(105, 100, 100), (byte) 10, "Box1");
        var id3 = tetree.insert(new Point3f(100, 105, 100), (byte) 10, "Capsule1");
        var id4 = tetree.insert(new Point3f(105, 105, 100), (byte) 10, "OBB1");

        // Set collision shapes
        tetree.setCollisionShape(id1, new SphereShape(new Point3f(100, 100, 100), 4.0f));
        tetree.setCollisionShape(id2, new BoxShape(new Point3f(105, 100, 100), new Vector3f(3, 3, 3)));
        tetree.setCollisionShape(id3, new CapsuleShape(new Point3f(100, 105, 100), 6.0f, 2.0f));
        
        var rotation = new Matrix3f();
        rotation.setIdentity();
        tetree.setCollisionShape(id4, new OrientedBoxShape(new Point3f(105, 105, 100), new Vector3f(3, 3, 3), rotation));

        // Find all collisions
        var allCollisions = tetree.findAllCollisions();
        assertTrue(allCollisions.size() >= 3, "Should find multiple collisions in cluster");

        // Find collisions for specific entity
        var sphereCollisions = tetree.findCollisions(id1);
        assertTrue(sphereCollisions.size() >= 2, "Sphere should collide with at least 2 other shapes");
    }

    @Test
    void testCollisionShapeWithEntityBounds() {
        // Test that custom collision shapes override entity bounds
        var pos = new Point3f(100, 100, 100);
        var bounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105)); // 10x10x10 box
        
        var id1 = idGenerator.generateID();
        tetree.insert(id1, pos, (byte) 10, "Entity1", bounds);

        var id2 = tetree.insert(new Point3f(112, 100, 100), (byte) 10, "Entity2");

        // Without custom shape, should not collide (12 units apart, bounds are 10 units wide)
        assertFalse(tetree.checkCollision(id1, id2).isPresent(), "Should not collide with default bounds");

        // Set larger sphere shape
        tetree.setCollisionShape(id1, new SphereShape(pos, 15.0f));

        // Now should collide
        assertTrue(tetree.checkCollision(id1, id2).isPresent(), "Should collide with larger sphere shape");
    }

    @Test
    void testCollisionShapeRemoval() {
        // Test removing collision shapes reverts to default behavior
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(100.08f, 100, 100); // Within 0.1f threshold

        var id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        var id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        // First verify default collision works
        var defaultCollision = tetree.checkCollision(id1, id2);
        assertTrue(defaultCollision.isPresent(), "Points should collide with default threshold before setting shapes");

        // Set small sphere shapes (no collision due to small size)
        tetree.setCollisionShape(id1, new SphereShape(pos1, 0.01f));
        tetree.setCollisionShape(id2, new SphereShape(pos2, 0.01f));

        assertFalse(tetree.checkCollision(id1, id2).isPresent(), "Small spheres should not collide");

        // Remove collision shapes
        tetree.setCollisionShape(id1, null);
        tetree.setCollisionShape(id2, null);

        // Should now use default point collision (0.1f threshold)
        var finalCollision = tetree.checkCollision(id1, id2);
        assertTrue(finalCollision.isPresent(), "Points should collide with default threshold after shape removal");
    }

    @Test
    void testNonExistentEntityCollisionShape() {
        // Test behavior with non-existent entities
        var fakeId = new LongEntityID(9999);
        
        // Should not throw when setting shape for non-existent entity
        assertDoesNotThrow(() -> tetree.setCollisionShape(fakeId, new SphereShape(new Point3f(0, 0, 0), 5.0f)));
        
        // Should return null for non-existent entity
        assertNull(tetree.getCollisionShape(fakeId), "Should return null for non-existent entity");
    }

    @Test
    void testTetrahedralConstraints() {
        // Test collision shapes respect tetrahedral positive coordinate constraints
        var pos1 = new Point3f(10, 10, 10); // Valid positive coordinates
        var pos2 = new Point3f(20, 10, 10);

        var id1 = tetree.insert(pos1, (byte) 10, "Sphere1");
        var id2 = tetree.insert(pos2, (byte) 10, "Sphere2");

        // Set overlapping spheres
        tetree.setCollisionShape(id1, new SphereShape(pos1, 8.0f));
        tetree.setCollisionShape(id2, new SphereShape(pos2, 8.0f));

        // Check collision works with positive coordinates
        var collision = tetree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Spheres should collide in tetrahedral space");
        assertEquals(6.0f, collision.get().penetrationDepth(), 0.1f, "Should have correct penetration depth");
    }
}