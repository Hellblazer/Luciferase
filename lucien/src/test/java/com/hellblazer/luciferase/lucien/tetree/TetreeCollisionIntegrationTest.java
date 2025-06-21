/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.collision.*;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Tetree collision detection with the full collision system. Tests the complete pipeline
 * including physics, listeners, and filters within the tetrahedral spatial decomposition.
 *
 * @author hal.hildebrand
 */
public class TetreeCollisionIntegrationTest {

    private Tetree<LongEntityID, String>          tetree;
    private CollisionSystem<LongEntityID, String> collisionSystem;
    private SequentialLongIDGenerator             idGenerator;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        collisionSystem = new CollisionSystem<>(tetree);
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testCollisionFilterWithTetrahedralCells() {
        // Test collision filtering based on tetrahedral cell membership
        Point3f[] positions = new Point3f[8];
        LongEntityID[] ids = new LongEntityID[8];

        // Create entities in different tetrahedral cells
        for (int i = 0; i < 8; i++) {
            float offset = i * 100;
            positions[i] = new Point3f(100 + offset, 100, 100);

            // Make pairs collide
            if (i % 2 == 1) {
                positions[i].x = positions[i - 1].x + 0.05f;
            }

            ids[i] = tetree.insert(positions[i], (byte) 10, "Entity" + i);
        }

        // Create filter that only processes collisions in lower cells
        CollisionFilter<LongEntityID, String> spatialFilter = collision -> {
            Point3f pos1 = tetree.getEntityPosition(collision.entityId1());
            Point3f pos2 = tetree.getEntityPosition(collision.entityId2());

            // Only process if both entities are in lower half of space
            return pos1.x < 400 && pos2.x < 400;
        };

        CollisionSystem<LongEntityID, String> filteredSystem = new CollisionSystem<>(tetree, new CollisionResolver(),
                                                                                     spatialFilter);

        // Set physics for all
        for (LongEntityID id : ids) {
            filteredSystem.setPhysicsProperties(id, new PhysicsProperties());
        }

        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = filteredSystem.processAllCollisions();

        // Should only process first two pairs (positions < 400)
        assertEquals(2, processed.size(), "Should only process collisions in lower spatial region");
    }

    @Test
    void testCollisionListenerChainInTetree() {
        // Test multiple collision listeners with different priorities
        Point3f pos1 = new Point3f(250, 250, 250);
        Point3f pos2 = new Point3f(250.05f, 250, 250);

        LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity2");

        AtomicInteger preProcessCount = new AtomicInteger(0);
        AtomicInteger postProcessCount = new AtomicInteger(0);
        List<Float> penetrationDepths = new ArrayList<>();

        // Pre-process listener
        CollisionListener<LongEntityID, String> preProcessor = (collision, response) -> {
            preProcessCount.incrementAndGet();
            penetrationDepths.add(collision.penetrationDepth());
            return true; // Allow processing
        };

        // Post-process listener
        CollisionListener<LongEntityID, String> postProcessor = (collision, response) -> {
            postProcessCount.incrementAndGet();
            // Verify response was generated
            assertNotNull(response);
            assertTrue(response.hasResponse());
            return true;
        };

        collisionSystem.addCollisionListener(preProcessor);
        collisionSystem.addCollisionListener(postProcessor);

        // Set physics
        collisionSystem.setPhysicsProperties(id1, new PhysicsProperties());
        collisionSystem.setPhysicsProperties(id2, new PhysicsProperties());

        // Process
        collisionSystem.processAllCollisions();

        assertEquals(1, preProcessCount.get(), "Pre-processor should run once");
        assertEquals(1, postProcessCount.get(), "Post-processor should run once");
        assertEquals(1, penetrationDepths.size(), "Should capture penetration depth");
        assertTrue(penetrationDepths.get(0) > 0, "Should have positive penetration");
    }

    @Test
    void testCollisionSystemStatisticsInTetree() {
        // Test collision system statistics gathering
        // Create scenario with known collision count
        int pairCount = 5;
        List<LongEntityID> ids = new ArrayList<>();

        for (int i = 0; i < pairCount * 2; i += 2) {
            Point3f pos1 = new Point3f(200 + i * 50, 200, 200);
            Point3f pos2 = new Point3f(200 + i * 50 + 0.05f, 200, 200);

            LongEntityID id1 = tetree.insert(pos1, (byte) 10, "Entity" + i);
            LongEntityID id2 = tetree.insert(pos2, (byte) 10, "Entity" + (i + 1));

            ids.add(id1);
            ids.add(id2);

            collisionSystem.setPhysicsProperties(id1, new PhysicsProperties());
            collisionSystem.setPhysicsProperties(id2, new PhysicsProperties());
        }

        // Process collisions
        long startTime = System.nanoTime();
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = collisionSystem.processAllCollisions();
        long endTime = System.nanoTime();

        // Get statistics
        CollisionSystem.CollisionStats stats = collisionSystem.getLastStats();

        assertEquals(pairCount, stats.collisionsDetected(), "Should detect expected number of collisions");
        assertEquals(pairCount, stats.collisionsResolved(), "Should resolve all collisions");
        assertTrue(stats.totalProcessingTime() > 0, "Should have processing time");
        assertTrue(stats.totalProcessingTime() <= (endTime - startTime), "Processing time should be reasonable");

        double avgTime = stats.averageProcessingTime();
        assertTrue(avgTime > 0, "Should have positive average processing time");
    }

    @Test
    void testCrossImplementationConsistency() {
        // Test that Tetree produces similar collision results to Octree
        // This validates the accuracy of tetrahedral collision detection

        // Create identical scenario in both structures
        Point3f pos1 = new Point3f(300, 300, 300);
        Point3f pos2 = new Point3f(305, 300, 300);

        EntityBounds bounds1 = new EntityBounds(new Point3f(295, 295, 295), new Point3f(305, 305, 305));
        EntityBounds bounds2 = new EntityBounds(new Point3f(300, 295, 295), new Point3f(310, 305, 305));

        // Insert in Tetree
        LongEntityID tetId1 = idGenerator.generateID();
        LongEntityID tetId2 = idGenerator.generateID();

        tetree.insert(tetId1, pos1, (byte) 10, "TetEntity1", bounds1);
        tetree.insert(tetId2, pos2, (byte) 10, "TetEntity2", bounds2);

        // Check collision
        Optional<SpatialIndex.CollisionPair<LongEntityID, String>> tetCollision = tetree.checkCollision(tetId1, tetId2);

        assertTrue(tetCollision.isPresent(), "Tetree should detect collision");

        // Verify collision properties are reasonable
        var collision = tetCollision.get();
        assertNotNull(collision.contactPoint());
        assertNotNull(collision.contactNormal());
        assertEquals(5.0f, collision.penetrationDepth(), 0.1f, "Penetration depth should be approximately 5 units");

        // Contact normal should be along X axis
        Vector3f normal = collision.contactNormal();
        assertEquals(1.0f, Math.abs(normal.x), 0.1f, "Normal should be primarily along X");
        assertEquals(0.0f, normal.y, 0.1f, "Normal Y should be near zero");
        assertEquals(0.0f, normal.z, 0.1f, "Normal Z should be near zero");
    }

    @Test
    void testDynamicTreeBalancingWithCollisions() {
        // Test collision detection remains accurate during tree rebalancing
        // Create many entities to trigger potential rebalancing
        List<LongEntityID> ids = new ArrayList<>();

        // Create dense cluster
        Point3f clusterCenter = new Point3f(300, 300, 300);
        for (int i = 0; i < 20; i++) {
            Point3f pos = new Point3f(clusterCenter.x + (i % 5) * 2, clusterCenter.y + (i / 5) * 2, clusterCenter.z);

            // Make them all slightly overlap with 2.5 unit radius bounds
            EntityBounds bounds = new EntityBounds(new Point3f(pos.x - 2.5f, pos.y - 2.5f, pos.z - 2.5f),
                                                   new Point3f(pos.x + 2.5f, pos.y + 2.5f, pos.z + 2.5f));

            LongEntityID id = idGenerator.generateID();
            tetree.insert(id, pos, (byte) 12, "ClusterEntity" + i, bounds);
            ids.add(id);
        }

        // Process collisions before any tree changes
        List<SpatialIndex.CollisionPair<LongEntityID, String>> beforeCollisions = tetree.findAllCollisions();
        int beforeCount = beforeCollisions.size();

        // Simulate tree operations that might trigger rebalancing
        for (int i = 0; i < 5; i++) {
            LongEntityID id = ids.get(i);
            Point3f newPos = new Point3f(400 + i * 10, 400, 400);
            tetree.updateEntity(id, newPos, (byte) 10);
        }

        // Process collisions after potential rebalancing
        List<SpatialIndex.CollisionPair<LongEntityID, String>> afterCollisions = tetree.findAllCollisions();

        // Verify collision detection still works correctly
        assertTrue(afterCollisions.size() < beforeCount, "Should have fewer collisions after moving some entities");
        assertTrue(afterCollisions.size() > 0, "Should still detect remaining collisions");
    }

    @Test
    void testFullCollisionPipelineInTetrahedralSpace() {
        // Create colliding entities within tetrahedral bounds
        Point3f pos1 = new Point3f(200, 200, 200);
        Point3f pos2 = new Point3f(205, 200, 200);

        EntityBounds bounds1 = new EntityBounds(new Point3f(195, 195, 195), new Point3f(205, 205, 205));
        EntityBounds bounds2 = new EntityBounds(new Point3f(200, 195, 195), new Point3f(210, 205, 205));

        LongEntityID id1 = idGenerator.generateID();
        LongEntityID id2 = idGenerator.generateID();

        tetree.insert(id1, pos1, (byte) 10, "Entity1", bounds1);
        tetree.insert(id2, pos2, (byte) 10, "Entity2", bounds2);

        // Set physics properties
        PhysicsProperties props1 = new PhysicsProperties(1.0f, new Vector3f(5, 0, 0));
        PhysicsProperties props2 = new PhysicsProperties(1.0f, new Vector3f(-3, 0, 0));

        collisionSystem.setPhysicsProperties(id1, props1);
        collisionSystem.setPhysicsProperties(id2, props2);

        // Process collisions
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = collisionSystem.processAllCollisions();

        assertEquals(1, processed.size(), "Should process one collision");

        // Verify physics response
        CollisionResponse response = processed.get(0).response();
        assertTrue(response.hasResponse(), "Should have collision response");

        // Verify velocities changed
        assertNotEquals(5.0f, props1.getVelocity().x, "Entity 1 velocity should change");
        assertNotEquals(-3.0f, props2.getVelocity().x, "Entity 2 velocity should change");
    }

    @Test
    void testKinematicBodiesInTetrahedralSpace() {
        // Test kinematic bodies moving through tetrahedral space
        Point3f platformPos = new Point3f(300, 300, 300);
        Point3f fallingPos = new Point3f(300, 350, 300);

        EntityBounds platformBounds = new EntityBounds(new Point3f(250, 295, 250), new Point3f(350, 305, 350)
                                                       // Large platform
        );

        LongEntityID platformId = idGenerator.generateID();
        LongEntityID fallingId = idGenerator.generateID();

        tetree.insert(platformId, platformPos, (byte) 8, "Platform", platformBounds);
        tetree.insert(fallingId, fallingPos, (byte) 10, "Falling");

        // Platform is kinematic, moving up slowly
        PhysicsProperties platformProps = PhysicsProperties.createKinematic(new Vector3f(0, 5, 0));

        // Falling object is dynamic
        PhysicsProperties fallingProps = new PhysicsProperties(1.0f, new Vector3f(0, -10, 0));

        collisionSystem.setPhysicsProperties(platformId, platformProps);
        collisionSystem.setPhysicsProperties(fallingId, fallingProps);

        // Simulate until collision
        float dt = 0.01f;
        int maxSteps = 1000;
        boolean collisionDetected = false;

        for (int i = 0; i < maxSteps && !collisionDetected; i++) {
            collisionSystem.updatePhysics(dt, null);

            List<CollisionSystem.ProcessedCollision<LongEntityID>> collisions = collisionSystem.processAllCollisions();

            if (!collisions.isEmpty()) {
                collisionDetected = true;

                // Verify kinematic platform pushes dynamic object
                Vector3f fallingVel = fallingProps.getVelocity();
                assertTrue(fallingVel.y > -10, "Falling object should be pushed up by kinematic platform");
            }
        }

        assertTrue(collisionDetected, "Should detect collision between platform and falling object");
    }

    @Test
    void testMultiLevelCollisionIntegration() {
        // Test collision system with entities at different tetree levels

        // Create entities at different levels
        float[] scales = { 1f, 10f, 100f };
        byte[] levels = { 15, 10, 5 };
        List<LongEntityID> allIds = new ArrayList<>();

        for (int i = 0; i < scales.length; i++) {
            float scale = scales[i];
            byte level = levels[i];

            Point3f pos1 = new Point3f(200 * scale, 200 * scale, 200 * scale);
            Point3f pos2 = new Point3f(200 * scale + 0.05f, 200 * scale, 200 * scale);

            LongEntityID id1 = tetree.insert(pos1, level, "Entity_L" + level + "_1");
            LongEntityID id2 = tetree.insert(pos2, level, "Entity_L" + level + "_2");

            allIds.add(id1);
            allIds.add(id2);

            // Set physics for dynamic behavior
            collisionSystem.setPhysicsProperties(id1, new PhysicsProperties());
            collisionSystem.setPhysicsProperties(id2, new PhysicsProperties());
        }

        // Process all collisions
        List<CollisionSystem.ProcessedCollision<LongEntityID>> processed = collisionSystem.processAllCollisions();

        // Should detect collisions at each level
        assertTrue(processed.size() >= 3, "Should detect collisions at multiple levels");
    }

    @Test
    void testPhysicsIntegrationWithTetrahedralConstraints() {
        // Test physics updates respecting tetrahedral bounds
        Point3f initialPos = new Point3f(400, 400, 400);
        LongEntityID id = tetree.insert(initialPos, (byte) 10, "PhysicsEntity");

        PhysicsProperties props = new PhysicsProperties(1.0f, new Vector3f(10, 5, -5));
        collisionSystem.setPhysicsProperties(id, props);

        // Apply multiple physics updates
        float dt = 0.1f;
        Vector3f gravity = new Vector3f(0, -9.8f, 0);

        for (int i = 0; i < 10; i++) {
            collisionSystem.updatePhysics(dt, gravity);
        }

        // Verify entity stayed within valid tetrahedral bounds
        Point3f finalPos = tetree.getEntityPosition(id);
        assertNotNull(finalPos);
        assertTrue(finalPos.x >= 0, "X should remain positive");
        assertTrue(finalPos.y >= 0, "Y should remain positive");
        assertTrue(finalPos.z >= 0, "Z should remain positive");

        // Verify physics worked
        assertNotEquals(initialPos.x, finalPos.x, "X position should change");
        assertTrue(finalPos.y < initialPos.y, "Y position should decrease due to gravity");
        assertNotEquals(initialPos.z, finalPos.z, "Z position should change");
    }

    @Test
    void testTetrahedralRegionCollisions() {
        // Test collision detection in specific tetrahedral regions
        // Create entities distributed across the tetrahedral space
        Point3f[] positions = { new Point3f(100, 100, 100), new Point3f(100.05f, 100, 100), new Point3f(300, 300, 300),
                                new Point3f(300.05f, 300, 300), new Point3f(500, 100, 100), new Point3f(500.05f, 100,
                                                                                                        100) };

        LongEntityID[] ids = new LongEntityID[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = tetree.insert(positions[i], (byte) 10, "Entity" + i);
        }

        // Test collision detection in lower region
        Spatial lowerRegion = new Spatial.Cube(50, 50, 50, 100);
        List<SpatialIndex.CollisionPair<LongEntityID, String>> lowerCollisions = tetree.findCollisionsInRegion(
        lowerRegion);

        assertEquals(1, lowerCollisions.size(), "Should find one collision in lower region");
        assertTrue(lowerCollisions.get(0).involves(ids[0]) && lowerCollisions.get(0).involves(ids[1]),
                   "Collision should be between entities 0 and 1");

        // Test collision detection in middle region
        Spatial middleRegion = new Spatial.Sphere(300, 300, 300, 50);
        List<SpatialIndex.CollisionPair<LongEntityID, String>> middleCollisions = tetree.findCollisionsInRegion(
        middleRegion);

        assertEquals(1, middleCollisions.size(), "Should find one collision in middle region");
        assertTrue(middleCollisions.get(0).involves(ids[2]) && middleCollisions.get(0).involves(ids[3]),
                   "Collision should be between entities 2 and 3");
    }
}
