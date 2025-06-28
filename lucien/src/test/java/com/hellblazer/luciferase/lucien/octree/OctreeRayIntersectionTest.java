/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialIndex;
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
 * Comprehensive tests for Octree ray intersection functionality
 *
 * @author hal.hildebrand
 */
public class OctreeRayIntersectionTest {

    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testBasicRayIntersection() {
        // Insert entities along a ray path
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(200, 200, 200);
        var pos3 = new Point3f(300, 300, 300);
        var level = (byte) 10;

        var id1 = octree.insert(pos1, level, "Entity1");
        var id2 = octree.insert(pos2, level, "Entity2");
        var id3 = octree.insert(pos3, level, "Entity3");

        // Create ray from origin through entities
        var ray = Ray3D.fromPointsUnbounded(new Point3f(50, 50, 50), new Point3f(350, 350, 350));

        // Test rayIntersectAll
        var allIntersections = octree.rayIntersectAll(ray);

        assertFalse(allIntersections.isEmpty(), "Ray should intersect with entities");
        assertEquals(3, allIntersections.size(), "Should find all 3 entities");

        // Verify intersections are sorted by distance
        for (int i = 1; i < allIntersections.size(); i++) {
            assertTrue(allIntersections.get(i - 1).distance() <= allIntersections.get(i).distance(),
                       "Intersections should be sorted by distance");
        }

        // Test rayIntersectFirst
        var firstIntersection = octree.rayIntersectFirst(ray);
        assertTrue(firstIntersection.isPresent(), "Should find first intersection");
        assertEquals(allIntersections.get(0).entityId(), firstIntersection.get().entityId(),
                     "First intersection should match first from rayIntersectAll");
    }

    @Test
    void testDifferentLevels() {
        // Insert entities at different octree levels
        var pos1 = new Point3f(100, 100, 100);
        var pos2 = new Point3f(200, 200, 200);

        var id1 = octree.insert(pos1, (byte) 8, "CoarseLevel");  // Coarse level
        var id2 = octree.insert(pos2, (byte) 15, "FineLevel");   // Fine level

        var ray = Ray3D.fromPointsUnbounded(new Point3f(50, 50, 50), new Point3f(350, 350, 350));

        var intersections = octree.rayIntersectAll(ray);

        assertEquals(2, intersections.size(), "Should find entities at different levels");

        // Verify both entities are found
        var foundCoarse = intersections.stream().anyMatch(i -> "CoarseLevel".equals(i.content()));
        var foundFine = intersections.stream().anyMatch(i -> "FineLevel".equals(i.content()));
        assertTrue(foundCoarse, "Should find coarse level entity");
        assertTrue(foundFine, "Should find fine level entity");
    }

    @Test
    void testMultipleEntitiesAtSamePosition() {
        // Insert multiple entities at the same position
        var pos = new Point3f(150, 150, 150);
        var level = (byte) 10;

        var id1 = octree.insert(pos, level, "Entity1");
        var id2 = octree.insert(pos, level, "Entity2");
        var id3 = octree.insert(pos, level, "Entity3");

        // Ray that intersects the position
        var ray = Ray3D.fromPoints(new Point3f(50, 50, 50), new Point3f(250, 250, 250));

        var intersections = octree.rayIntersectAll(ray);

        assertEquals(3, intersections.size(), "Should find all entities at the same position");

        // All should have approximately the same distance
        var expectedDistance = intersections.get(0).distance();
        for (var intersection : intersections) {
            assertEquals(expectedDistance, intersection.distance(), 1.0f,
                         "All entities at same position should have similar distance");
        }
    }

    @Test
    void testNoIntersections() {
        // Insert entity
        var pos = new Point3f(100, 100, 100);
        octree.insert(pos, (byte) 10, "Entity");

        // Ray that doesn't intersect (pointing away from different position)
        var ray = new Ray3D(new Point3f(200, 100, 100), new Vector3f(-1, 0, 0), 50);

        var intersections = octree.rayIntersectAll(ray);
        assertTrue(intersections.isEmpty(), "Ray pointing away should not intersect");

        var firstIntersection = octree.rayIntersectFirst(ray);
        assertFalse(firstIntersection.isPresent(), "Should find no first intersection");
    }

    @Test
    void testRayFromEntityCenter() {
        // Insert entity
        var entityPos = new Point3f(200, 200, 200);
        var entityId = octree.insert(entityPos, (byte) 10, "CenterEntity");

        // Ray starting from entity center
        var ray = new Ray3D(entityPos, new Vector3f(1, 0, 0), 100);

        var intersections = octree.rayIntersectAll(ray);

        // Should find the entity at the ray origin
        assertFalse(intersections.isEmpty(), "Should find entity at ray origin");
        assertEquals(1, intersections.size(), "Should find exactly one entity");
        assertEquals(entityId, intersections.get(0).entityId(), "Should find the correct entity");

        // For point entities, ray starting at entity center returns distance 0
        assertEquals(0.0f, intersections.get(0).distance(), 0.001f,
                     "Distance should be 0 for ray starting at point entity center");
    }

    @Test
    void testRayIntersectionAccuracy() {
        // Test intersection point accuracy
        var entityPos = new Point3f(100, 100, 100);
        var bounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));
        var level = (byte) 12; // Finer level for better accuracy

        var entityId = idGenerator.generateID();
        octree.insert(entityId, entityPos, level, "AccuracyTest", bounds);

        // Ray that should intersect the entity bounds
        var rayOrigin = new Point3f(50, 100, 100);
        var rayDirection = new Vector3f(1, 0, 0); // Pointing towards entity
        var ray = new Ray3D(rayOrigin, rayDirection, 200);

        var intersections = octree.rayIntersectAll(ray);

        assertFalse(intersections.isEmpty(), "Should find intersection");
        var intersection = intersections.get(0);

        // Verify intersection point is reasonable
        assertNotNull(intersection.intersectionPoint(), "Should have intersection point");

        // The intersection point should be on the ray
        var t = intersection.distance();
        var expectedPoint = ray.getPointAt(t);
        assertEquals(expectedPoint.x, intersection.intersectionPoint().x, 1.0f,
                     "Intersection point X should match ray calculation");
        assertEquals(expectedPoint.y, intersection.intersectionPoint().y, 1.0f,
                     "Intersection point Y should match ray calculation");
        assertEquals(expectedPoint.z, intersection.intersectionPoint().z, 1.0f,
                     "Intersection point Z should match ray calculation");
    }

    @Test
    void testRayIntersectionWithBounds() {
        // Create entities with bounds that should intersect the ray
        var pos = new Point3f(100, 100, 100);
        var bounds = new EntityBounds(new Point3f(90, 90, 90), new Point3f(110, 110, 110));
        var level = (byte) 10;

        var entityId = idGenerator.generateID();
        octree.insert(entityId, pos, level, "BoundedEntity", bounds);

        // Ray that should intersect the entity bounds
        var ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 200);

        var intersections = octree.rayIntersectAll(ray);

        assertFalse(intersections.isEmpty(), "Ray should intersect bounded entity");
        assertEquals(1, intersections.size(), "Should find exactly one entity");
        assertEquals(entityId, intersections.get(0).entityId(), "Should find the correct entity");
        assertNotNull(intersections.get(0).bounds(), "Intersection should include bounds");
    }

    @Test
    void testRayIntersectionWithinDistance() {
        // Insert entities at various distances
        var pos1 = new Point3f(100, 100, 100); // Closer
        var pos2 = new Point3f(200, 200, 200); // Medium distance
        var pos3 = new Point3f(400, 400, 400); // Farther
        var level = (byte) 10;

        octree.insert(pos1, level, "Close");
        octree.insert(pos2, level, "Medium");
        octree.insert(pos3, level, "Far");

        // Ray from origin
        var ray = Ray3D.fromPointsUnbounded(new Point3f(50, 50, 50), new Point3f(500, 500, 500));

        // Test with limited distance - should only find closer entities
        var maxDistance = 250.0f;
        var nearIntersections = octree.rayIntersectWithin(ray,
                                                                                                               maxDistance);

        // Should find only entities within the distance limit
        assertTrue(nearIntersections.size() >= 1, "Should find at least the close entity");
        assertTrue(nearIntersections.size() <= 2, "Should not find the far entity");

        // Verify all intersections are within distance
        for (var intersection : nearIntersections) {
            assertTrue(intersection.distance() <= maxDistance, "All intersections should be within max distance");
        }

        // Test with larger distance - should find all entities
        var allIntersections = octree.rayIntersectWithin(ray,
                                                                                                              1000.0f);
        assertEquals(3, allIntersections.size(), "Should find all entities with large distance");
    }

    @Test
    void testRayWithMaxDistance() {
        // Insert entities at different distances
        var closePos = new Point3f(100, 100, 100);
        var farPos = new Point3f(300, 300, 300);
        var level = (byte) 10;

        octree.insert(closePos, level, "Close");
        octree.insert(farPos, level, "Far");

        // Ray with limited max distance
        var maxDist = 200.0f;
        var ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), maxDist);

        var intersections = octree.rayIntersectAll(ray);

        // Should only find the close entity
        assertEquals(1, intersections.size(), "Should only find entity within ray max distance");
        assertEquals("Close", intersections.get(0).content(), "Should find the close entity");
    }
}
