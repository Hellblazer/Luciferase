/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.collision.BoxShape;
import com.hellblazer.luciferase.lucien.collision.OrientedBoxShape;
import com.hellblazer.luciferase.lucien.collision.SphereShape;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accuracy tests for Octree collision detection. Validates geometric accuracy, contact points, normals, and penetration
 * depths.
 *
 * @author hal.hildebrand
 */
public class OctreeCollisionAccuracyTest {

    private static final float EPSILON = 0.001f;

    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testCollisionResponseVectorAccuracy() {
        // Test that collision normal and penetration depth can be used for separation
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(107, 100, 100);

        EntityBounds bounds1 = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));
        EntityBounds bounds2 = new EntityBounds(new Point3f(102, 95, 95), new Point3f(112, 105, 105));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "Box1", bounds1);
        octree.insert(id2, center2, (byte) 10, "Box2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent());

        var pair = collision.get();

        // Apply minimum separation with a small buffer
        Vector3f separation = new Vector3f(pair.contactNormal());
        separation.scale(pair.penetrationDepth() * 0.5f + 0.1f); // Add small buffer

        Point3f newCenter1 = new Point3f(center1);
        newCenter1.sub(separation);

        Point3f newCenter2 = new Point3f(center2);
        newCenter2.add(separation);

        // Update positions
        octree.updateEntity(id1, newCenter1, (byte) 10);
        octree.updateEntity(id2, newCenter2, (byte) 10);

        // Verify they no longer collide
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> afterSeparation = octree.checkCollision(id1, id2);
        assertFalse(afterSeparation.isPresent(),
                    "Entities should not collide after applying separation based on collision data");
    }

    @Test
    void testContactNormalAccuracy() {
        // Create two AABBs colliding from known direction
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(109, 100, 100); // Approaching from +X direction

        EntityBounds bounds1 = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));
        EntityBounds bounds2 = new EntityBounds(new Point3f(104, 95, 95),   // 1 unit overlap on X
                                                new Point3f(114, 105, 105));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "Box1", bounds1);
        octree.insert(id2, center2, (byte) 10, "Box2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent());

        Vector3f normal = collision.get().contactNormal();
        assertNotNull(normal, "Contact normal should not be null");

        // Normal should point along X axis (either +X or -X)
        assertEquals(1.0f, Math.abs(normal.x), EPSILON, "Normal should be along X axis");
        assertEquals(0.0f, normal.y, EPSILON, "Normal Y component should be zero");
        assertEquals(0.0f, normal.z, EPSILON, "Normal Z component should be zero");

        // Verify normal is normalized
        float length = normal.length();
        assertEquals(1.0f, length, EPSILON, "Normal should be unit length");
    }

    @Test
    void testContactPointAccuracyForAABBCollision() {
        // Create two AABBs with known overlap
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(108, 100, 100); // 8 units apart on X axis

        EntityBounds bounds1 = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105)  // 10x10x10 box
        );
        EntityBounds bounds2 = new EntityBounds(new Point3f(103, 95, 95),   // Overlaps by 2 units on X axis
                                                new Point3f(113, 105, 105)  // 10x10x10 box
        );

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "Box1", bounds1);
        octree.insert(id2, center2, (byte) 10, "Box2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping AABBs should collide");

        var pair = collision.get();

        // Verify contact point is in the overlap region
        Point3f contactPoint = pair.contactPoint();
        assertNotNull(contactPoint, "Contact point should not be null");

        // Contact point should be in the overlap region (103-105 on X, 95-105 on Y and Z)
        assertTrue(contactPoint.x >= 103 - EPSILON && contactPoint.x <= 105 + EPSILON,
                   "Contact point X should be in overlap region");
        assertTrue(contactPoint.y >= 95 - EPSILON && contactPoint.y <= 105 + EPSILON,
                   "Contact point Y should be in overlap region");
        assertTrue(contactPoint.z >= 95 - EPSILON && contactPoint.z <= 105 + EPSILON,
                   "Contact point Z should be in overlap region");
    }

    @Test
    void testMixedShapeCollisionAccuracy() {
        // Test collision between box and sphere
        Point3f boxCenter = new Point3f(100, 100, 100);
        Point3f sphereCenter = new Point3f(105.5f, 100, 100);

        EntityBounds boxBounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));

        LongEntityID boxId = idGenerator.generateID();
        LongEntityID sphereId = idGenerator.generateID();

        octree.insert(boxId, boxCenter, (byte) 10, "Box", boxBounds);
        octree.insert(sphereId, sphereCenter, (byte) 10, "Sphere");

        // Set collision shapes
        BoxShape boxShape = new BoxShape(boxCenter, new Vector3f(10, 10, 10));
        SphereShape sphereShape = new SphereShape(sphereCenter, 1.0f);

        octree.setCollisionShape(boxId, boxShape);
        octree.setCollisionShape(sphereId, sphereShape);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(boxId, sphereId);
        assertTrue(collision.isPresent(), "Box and sphere should collide");

        // Verify collision properties
        var pair = collision.get();
        assertNotNull(pair.contactPoint());
        assertNotNull(pair.contactNormal());
        assertTrue(pair.penetrationDepth() > 0);
    }

    @Test
    void testMultipleCollisionAccuracy() {
        // Test accuracy with multiple simultaneous collisions
        Point3f center = new Point3f(100, 100, 100);

        // Create a central box
        EntityBounds centralBounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));

        LongEntityID centralId = idGenerator.generateID();
        octree.insert(centralId, center, (byte) 10, "Central", centralBounds);

        // Create surrounding boxes that all collide with central
        Point3f[] surroundingCenters = { new Point3f(108, 100, 100), // +X
                                         new Point3f(92, 100, 100),  // -X
                                         new Point3f(100, 108, 100), // +Y
                                         new Point3f(100, 92, 100),  // -Y
                                         new Point3f(100, 100, 108), // +Z
                                         new Point3f(100, 100, 92)   // -Z
        };

        LongEntityID[] surroundingIds = new LongEntityID[6];
        for (int i = 0; i < 6; i++) {
            EntityBounds bounds = new EntityBounds(
            new Point3f(surroundingCenters[i].x - 5, surroundingCenters[i].y - 5, surroundingCenters[i].z - 5),
            new Point3f(surroundingCenters[i].x + 5, surroundingCenters[i].y + 5, surroundingCenters[i].z + 5));

            surroundingIds[i] = idGenerator.generateID();
            octree.insert(surroundingIds[i], surroundingCenters[i], (byte) 10, "Surround" + i, bounds);
        }

        // Test all collisions
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findCollisions(centralId);
        assertEquals(6, allCollisions.size(), "Central box should collide with all 6 surrounding boxes");

        // Verify each collision has accurate normals
        for (var collision : allCollisions) {
            Vector3f normal = collision.contactNormal();
            assertNotNull(normal);

            // Normal should be aligned with one axis
            int alignedAxis = 0;
            if (Math.abs(normal.x) > 0.9f) {
                alignedAxis++;
            }
            if (Math.abs(normal.y) > 0.9f) {
                alignedAxis++;
            }
            if (Math.abs(normal.z) > 0.9f) {
                alignedAxis++;
            }

            assertEquals(1, alignedAxis, "Normal should be aligned with exactly one axis");

            // Verify penetration depth is consistent (should be 2 units for all)
            assertEquals(2.0f, collision.penetrationDepth(), EPSILON, "All penetration depths should be 2 units");
        }
    }

    @Test
    void testNumericalScaleInvariance() {
        // Test that collision detection is accurate at different scales
        float[] scales = { 0.001f, 1.0f, 1000.0f };

        for (float scale : scales) {
            Octree<LongEntityID, String> scaledOctree = new Octree<>(new SequentialLongIDGenerator());

            Point3f center1 = new Point3f(100 * scale, 100 * scale, 100 * scale);
            Point3f center2 = new Point3f(108 * scale, 100 * scale, 100 * scale);

            EntityBounds bounds1 = new EntityBounds(new Point3f(95 * scale, 95 * scale, 95 * scale),
                                                    new Point3f(105 * scale, 105 * scale, 105 * scale));
            EntityBounds bounds2 = new EntityBounds(new Point3f(103 * scale, 95 * scale, 95 * scale),
                                                    new Point3f(113 * scale, 105 * scale, 105 * scale));

            LongEntityID id1 = idGenerator.generateID();
            LongEntityID id2 = idGenerator.generateID();

            scaledOctree.insert(id1, center1, (byte) 10, "Box1", bounds1);
            scaledOctree.insert(id2, center2, (byte) 10, "Box2", bounds2);

            Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = scaledOctree.checkCollision(id1,
                                                                                                               id2);
            assertTrue(collision.isPresent(), "Collision should be detected at scale " + scale);

            // Verify scaled penetration depth
            float expectedPenetration = 2.0f * scale;
            assertEquals(expectedPenetration, collision.get().penetrationDepth(), EPSILON * scale,
                         "Penetration depth should scale correctly at scale " + scale);
        }
    }

    @Test
    void testOrientedBoxCollisionAccuracy() {
        // Test collision with oriented bounding boxes
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(105, 100, 100);

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "OBB1");
        octree.insert(id2, center2, (byte) 10, "OBB2");

        // Create oriented box shapes
        Vector3f halfExtents = new Vector3f(5, 5, 5);

        // Identity matrix for first box
        Matrix3f orientation1 = new Matrix3f();
        orientation1.setIdentity();

        // Second box rotated 45 degrees around Y axis
        float angle = (float) Math.PI / 4;
        Matrix3f orientation2 = new Matrix3f();
        orientation2.setIdentity();
        orientation2.m00 = (float) Math.cos(angle);
        orientation2.m02 = (float) Math.sin(angle);
        orientation2.m20 = -(float) Math.sin(angle);
        orientation2.m22 = (float) Math.cos(angle);

        OrientedBoxShape obb1 = new OrientedBoxShape(center1, halfExtents, orientation1);
        OrientedBoxShape obb2 = new OrientedBoxShape(center2, halfExtents, orientation2);

        octree.setCollisionShape(id1, obb1);
        octree.setCollisionShape(id2, obb2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Oriented boxes should collide");

        // Verify collision properties make sense
        var pair = collision.get();
        assertNotNull(pair.contactPoint());
        assertNotNull(pair.contactNormal());
        assertTrue(pair.penetrationDepth() > 0);

        // Normal should be unit length
        assertEquals(1.0f, pair.contactNormal().length(), EPSILON);
    }

    @Test
    void testPenetrationDepthAccuracy() {
        // Create AABBs with known penetration depth
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(107, 100, 100); // Centers 7 units apart

        EntityBounds bounds1 = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105)
                                                // Right edge at x=105
        );
        EntityBounds bounds2 = new EntityBounds(new Point3f(102, 95, 95),   // Left edge at x=102, so 3 units overlap
                                                new Point3f(112, 105, 105));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        octree.insert(id1, center1, (byte) 10, "Box1", bounds1);
        octree.insert(id2, center2, (byte) 10, "Box2", bounds2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent());

        float penetrationDepth = collision.get().penetrationDepth();
        assertEquals(3.0f, penetrationDepth, EPSILON, "Penetration depth should be 3 units");
    }

    @Test
    void testSphereCollisionAccuracy() {
        // Test collision with sphere collision shapes
        Point3f center1 = new Point3f(100, 100, 100);
        Point3f center2 = new Point3f(103, 100, 100); // 3 units apart

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        // Insert entities
        octree.insert(id1, center1, (byte) 10, "Sphere1");
        octree.insert(id2, center2, (byte) 10, "Sphere2");

        // Set sphere collision shapes
        SphereShape sphere1 = new SphereShape(center1, 2.0f); // Radius 2
        SphereShape sphere2 = new SphereShape(center2, 2.0f); // Radius 2

        octree.setCollisionShape(id1, sphere1);
        octree.setCollisionShape(id2, sphere2);

        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> collision = octree.checkCollision(id1, id2);
        assertTrue(collision.isPresent(), "Overlapping spheres should collide");

        var pair = collision.get();

        // Verify contact point is on the line between centers
        Point3f contactPoint = pair.contactPoint();
        Vector3f centerLine = new Vector3f();
        centerLine.sub(center2, center1);
        centerLine.normalize();

        // Contact point should be between the two centers
        Vector3f toContact = new Vector3f();
        toContact.sub(contactPoint, center1);
        float projection = toContact.dot(centerLine);

        assertTrue(projection > 0 && projection < 3.0f, "Contact point should be between sphere centers");

        // Verify penetration depth
        float expectedPenetration = 4.0f - 3.0f; // Sum of radii - distance
        assertEquals(expectedPenetration, pair.penetrationDepth(), EPSILON, "Penetration depth should be 1 unit");
    }
}
