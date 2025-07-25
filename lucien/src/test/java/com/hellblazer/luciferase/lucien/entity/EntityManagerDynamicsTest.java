/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.entity;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.occlusion.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for EntityManager integration with EntityDynamics and DSOC components
 *
 * @author hal.hildebrand
 */
public class EntityManagerDynamicsTest {

    private EntityManager<MortonKey, LongEntityID, String> entityManager;
    private Map<LongEntityID, EntityDynamics> dynamicsMap;
    private SequentialLongIDGenerator idGenerator;
    private long baseTime;

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        entityManager = new EntityManager<>(idGenerator);
        dynamicsMap = new HashMap<>();
        baseTime = System.currentTimeMillis();
    }

    @Test
    void testEntityDynamicsIntegration() {
        // Create entity with dynamics tracking
        var entityId = new LongEntityID(1);
        var position1 = new Point3f(0, 0, 0);
        entityManager.createOrUpdateEntity(entityId, "TestEntity", position1, null);
        
        var dynamics = new EntityDynamics();
        dynamicsMap.put(entityId, dynamics);
        dynamics.updatePosition(position1, baseTime);

        // Simulate movement
        var position2 = new Point3f(10, 0, 0);
        entityManager.updateEntityPosition(entityId, position2);
        dynamics.updatePosition(position2, baseTime + 1000);

        // Verify velocity calculation
        var velocity = dynamics.getVelocity();
        assertEquals(10.0f, velocity.x, 0.01f);
        assertEquals(0.0f, velocity.y, 0.01f);
        assertEquals(0.0f, velocity.z, 0.01f);

        // Predict future position
        var predictedPosition = dynamics.predictPosition(2.0f);
        assertEquals(30.0f, predictedPosition.x, 0.01f);
    }

    @Test
    void testMultipleEntitiesWithDynamics() {
        var numEntities = 100;
        var entities = new ArrayList<LongEntityID>();

        // Create entities with different velocities
        for (int i = 0; i < numEntities; i++) {
            var id = new LongEntityID(i);
            entities.add(id);
            
            var position = new Point3f(i * 10, 0, 0);
            entityManager.createOrUpdateEntity(id, "Entity" + i, position, null);
            
            var dynamics = new EntityDynamics();
            dynamicsMap.put(id, dynamics);
            dynamics.updatePosition(position, baseTime);
        }

        // Update positions to create velocities
        for (int i = 0; i < numEntities; i++) {
            var id = entities.get(i);
            var newPosition = new Point3f(i * 10 + 5, i * 2, 0);
            entityManager.updateEntityPosition(id, newPosition);
            dynamicsMap.get(id).updatePosition(newPosition, baseTime + 1000);
        }

        // Verify all entities have dynamics
        for (var id : entities) {
            var dynamics = dynamicsMap.get(id);
            assertNotNull(dynamics);
            assertTrue(dynamics.isMoving(0.1f));
        }
    }

    @Test
    void testTBVCreationFromDynamics() {
        var entityId = new LongEntityID(1);
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        entityManager.createOrUpdateEntity(entityId, "TestEntity", bounds.getCenter(), bounds);

        var dynamics = new EntityDynamics();
        dynamicsMap.put(entityId, dynamics);

        // Create movement history
        dynamics.updatePosition(new Point3f(0, 0, 0), baseTime);
        dynamics.updatePosition(new Point3f(10, 0, 0), baseTime + 1000);
        dynamics.updatePosition(new Point3f(30, 0, 0), baseTime + 2000);

        // Create TBV from current dynamics
        var velocity = dynamics.getVelocity();
        var strategy = FixedDurationTBVStrategy.defaultStrategy();
        var tbv = new TemporalBoundingVolume(entityId, bounds, velocity, 100, strategy);

        assertNotNull(tbv);
        assertEquals(entityId, tbv.getEntityId());
        assertTrue(tbv.getValidityDuration() > 0);

        // Verify expanded bounds account for velocity
        var expanded = tbv.getExpandedBounds();
        assertTrue(expanded.getMax().x > bounds.getMax().x);
    }

    @Test
    void testDynamicsBasedTBVStrategy() {
        // Create entities with different movement patterns
        var stationaryId = new LongEntityID(1);
        var slowId = new LongEntityID(2);
        var fastId = new LongEntityID(3);

        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        
        entityManager.createOrUpdateEntity(stationaryId, "Stationary", new Point3f(0, 0, 0), bounds);
        entityManager.createOrUpdateEntity(slowId, "Slow", new Point3f(100, 0, 0), bounds);
        entityManager.createOrUpdateEntity(fastId, "Fast", new Point3f(200, 0, 0), bounds);

        // Create dynamics for each
        var stationaryDynamics = new EntityDynamics();
        var slowDynamics = new EntityDynamics();
        var fastDynamics = new EntityDynamics();

        dynamicsMap.put(stationaryId, stationaryDynamics);
        dynamicsMap.put(slowId, slowDynamics);
        dynamicsMap.put(fastId, fastDynamics);

        // Update positions to create different velocities
        for (int i = 0; i < 3; i++) {
            stationaryDynamics.updatePosition(new Point3f(0, 0, 0), baseTime + i * 1000);
            slowDynamics.updatePosition(new Point3f(100 + i * 2, 0, 0), baseTime + i * 1000);
            fastDynamics.updatePosition(new Point3f(200 + i * 20, 0, 0), baseTime + i * 1000);
        }

        // Use adaptive strategy to create TBVs
        var adaptiveStrategy = AdaptiveTBVStrategy.defaultStrategy();
        
        var stationaryTBV = new TemporalBoundingVolume(stationaryId, bounds, 
                                                      stationaryDynamics.getVelocity(), 
                                                      100, adaptiveStrategy);
        var slowTBV = new TemporalBoundingVolume(slowId, bounds, 
                                                slowDynamics.getVelocity(), 
                                                100, adaptiveStrategy);
        var fastTBV = new TemporalBoundingVolume(fastId, bounds, 
                                                fastDynamics.getVelocity(), 
                                                100, adaptiveStrategy);

        // Stationary entity should get longer validity
        assertTrue(stationaryTBV.getValidityDuration() > fastTBV.getValidityDuration());
        
        // Fast entity should get larger expansion
        assertTrue(fastTBV.getExpansionFactor() > stationaryTBV.getExpansionFactor());
    }

    @Test
    void testConcurrentDynamicsUpdates() throws InterruptedException {
        var numThreads = 10;
        var entitiesPerThread = 100;
        var latch = new CountDownLatch(numThreads);
        var errors = new AtomicInteger(0);

        // Use concurrent map for thread safety
        var concurrentDynamicsMap = new ConcurrentHashMap<LongEntityID, EntityDynamics>();

        // Create entities
        for (int i = 0; i < numThreads * entitiesPerThread; i++) {
            var id = new LongEntityID(i);
            entityManager.createOrUpdateEntity(id, "Entity" + i, new Point3f(i, i, i), null);
            concurrentDynamicsMap.put(id, new EntityDynamics());
        }

        // Update positions concurrently
        for (int t = 0; t < numThreads; t++) {
            var threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < entitiesPerThread; i++) {
                        var entityIndex = threadId * entitiesPerThread + i;
                        var id = new LongEntityID(entityIndex);
                        
                        // Update position in entity manager
                        var newPos = new Point3f(entityIndex + 10, entityIndex, entityIndex);
                        entityManager.updateEntityPosition(id, newPos);
                        
                        // Update dynamics
                        var dynamics = concurrentDynamicsMap.get(id);
                        dynamics.updatePosition(newPos, baseTime + threadId * 100);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, errors.get());

        // Verify all entities were updated
        assertEquals(numThreads * entitiesPerThread, entityManager.getEntityCount());
        assertEquals(numThreads * entitiesPerThread, concurrentDynamicsMap.size());
    }

    @Test
    void testDynamicsWithEntityLifecycle() {
        var entityId = new LongEntityID(1);
        
        // Create entity and dynamics
        entityManager.createOrUpdateEntity(entityId, "TestEntity", new Point3f(0, 0, 0), null);
        var dynamics = new EntityDynamics();
        dynamicsMap.put(entityId, dynamics);

        // Update position multiple times
        for (int i = 0; i < 5; i++) {
            var position = new Point3f(i * 10, 0, 0);
            entityManager.updateEntityPosition(entityId, position);
            dynamics.updatePosition(position, baseTime + i * 1000);
        }

        // Verify dynamics history
        assertEquals(5, dynamics.getHistoryCount());
        assertTrue(dynamics.isMoving(0.1f));

        // Remove entity
        entityManager.removeEntity(entityId);
        dynamicsMap.remove(entityId);

        // Verify cleanup
        assertNull(entityManager.getEntity(entityId));
        assertNull(dynamicsMap.get(entityId));
    }

    @Test
    void testPredictiveUpdateStrategy() {
        var config = DSOCConfiguration.defaultConfig()
            .withPredictiveUpdates(true)
            .withPredictiveUpdateLookahead(60);

        var entityId = new LongEntityID(1);
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        entityManager.createOrUpdateEntity(entityId, "TestEntity", bounds.getCenter(), bounds);

        var dynamics = new EntityDynamics();
        dynamicsMap.put(entityId, dynamics);

        // Create consistent movement pattern
        for (int i = 0; i < 10; i++) {
            var position = new Point3f(i * 5, i * 2, 0);
            dynamics.updatePosition(position, baseTime + i * 100);
        }

        // Use dynamics for predictive TBV creation
        var currentVelocity = dynamics.getVelocity();
        var predictedVelocity = dynamics.predictVelocity(
            config.getPredictiveUpdateLookahead() / 60.0f);

        // Create TBV with predicted velocity
        var tbv = new TemporalBoundingVolume(entityId, bounds, predictedVelocity, 
                                           100, config.getTbvStrategy());

        // Verify TBV accounts for predicted movement
        assertNotNull(tbv);
        assertTrue(tbv.getExpandedBounds().getMax().x > bounds.getMax().x);
    }

    @Test
    void testDynamicsMemoryEfficiency() {
        // Test circular buffer behavior with limited history
        var historySize = 10;
        var dynamics = new EntityDynamics(historySize);
        
        // Add many more positions than history size
        for (int i = 0; i < 1000; i++) {
            dynamics.updatePosition(new Point3f(i, 0, 0), baseTime + i * 100);
        }

        // History should be capped at historySize
        assertEquals(historySize, dynamics.getHistoryCount());

        // Should still calculate velocity correctly from recent history
        assertTrue(dynamics.getVelocity().x > 0);
    }

    @Test
    void testEntityRegionQueriesWithDynamics() {
        // Create moving entities
        var numEntities = 50;
        for (int i = 0; i < numEntities; i++) {
            var id = new LongEntityID(i);
            var initialPos = new Point3f(i * 20, 0, 0);
            entityManager.createOrUpdateEntity(id, "Entity" + i, initialPos, null);
            
            var dynamics = new EntityDynamics();
            dynamicsMap.put(id, dynamics);
            dynamics.updatePosition(initialPos, baseTime);
        }

        // Move entities
        for (int i = 0; i < numEntities; i++) {
            var id = new LongEntityID(i);
            var newPos = new Point3f(i * 20 + 10, 5, 0);
            entityManager.updateEntityPosition(id, newPos);
            dynamicsMap.get(id).updatePosition(newPos, baseTime + 1000);
        }

        // Query region and check dynamics
        var entitiesInRegion = entityManager.findEntitiesInRegion(
            100, 200,  // x range
            0, 10,     // y range
            -10, 10    // z range
        );

        assertFalse(entitiesInRegion.isEmpty());

        // Verify entities in region have dynamics
        for (var id : entitiesInRegion) {
            var dynamics = dynamicsMap.get(id);
            assertNotNull(dynamics);
            assertTrue(dynamics.isMoving(0.1f));
        }
    }

    @Test
    void testDynamicsBasedSpatialCoherence() {
        var config = DSOCConfiguration.defaultConfig()
            .withSpatialCoherence(true)
            .withSpatialCoherenceRadius(50.0f);

        // Create cluster of entities moving together
        var clusterCenter = new Point3f(100, 100, 100);
        var clusterIds = new ArrayList<LongEntityID>();
        
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(i);
            clusterIds.add(id);
            
            var offset = new Point3f(i * 5, 0, 0);
            var position = new Point3f();
            position.add(clusterCenter, offset);
            
            entityManager.createOrUpdateEntity(id, "ClusterEntity" + i, position, null);
            
            var dynamics = new EntityDynamics();
            dynamicsMap.put(id, dynamics);
            dynamics.updatePosition(position, baseTime);
        }

        // Move cluster together
        var clusterVelocity = new Vector3f(10, 5, 0);
        for (var id : clusterIds) {
            var currentPos = entityManager.getEntityPosition(id);
            var newPos = new Point3f();
            newPos.add(currentPos, clusterVelocity);
            
            entityManager.updateEntityPosition(id, newPos);
            dynamicsMap.get(id).updatePosition(newPos, baseTime + 1000);
        }

        // Verify all entities in cluster have similar velocities
        var velocities = new ArrayList<Vector3f>();
        for (var id : clusterIds) {
            velocities.add(dynamicsMap.get(id).getVelocity());
        }

        // Check velocity coherence
        for (int i = 1; i < velocities.size(); i++) {
            var v1 = velocities.get(0);
            var v2 = velocities.get(i);
            var diff = new Vector3f();
            diff.sub(v1, v2);
            assertTrue(diff.length() < 1.0f); // Should have very similar velocities
        }
    }

    @Test
    void testDynamicsWithAcceleration() {
        var entityId = new LongEntityID(1);
        entityManager.createOrUpdateEntity(entityId, "AcceleratingEntity", 
                                         new Point3f(0, 0, 0), null);
        
        var dynamics = new EntityDynamics();
        dynamicsMap.put(entityId, dynamics);

        // Create accelerating motion (quadratic position change)
        for (int i = 0; i < 5; i++) {
            var position = new Point3f(i * i * 5, 0, 0); // x = 5t²
            entityManager.updateEntityPosition(entityId, position);
            dynamics.updatePosition(position, baseTime + i * 1000);
        }

        // Verify acceleration is detected
        assertTrue(dynamics.isAccelerating(0.1f));
        
        // Acceleration should be constant for quadratic motion
        var acceleration = dynamics.getAcceleration();
        assertTrue(acceleration.x > 0);

        // Test prediction with acceleration
        var predictedPos = dynamics.predictPosition(1.0f);
        var predictedVel = dynamics.predictVelocity(1.0f);
        
        assertNotNull(predictedPos);
        assertNotNull(predictedVel);
        assertTrue(predictedVel.x > dynamics.getVelocity().x);
    }

    @Test
    void testCompleteIntegrationScenario() {
        // Simulate a complete scenario with entity creation, movement, and TBV management
        var dsocConfig = DSOCConfiguration.dynamicScene();
        var tbvMap = new HashMap<LongEntityID, TemporalBoundingVolume>();
        
        // Phase 1: Create entities
        var numEntities = 20;
        for (int i = 0; i < numEntities; i++) {
            var id = new LongEntityID(i);
            var position = new Point3f(i * 50, (i % 5) * 20, 0);
            var bounds = new EntityBounds(position, 5.0f);
            
            entityManager.createOrUpdateEntity(id, "Entity" + i, position, bounds);
            dynamicsMap.put(id, new EntityDynamics());
        }

        // Phase 2: Initial movement
        var currentFrame = 0;
        for (int frame = 0; frame < 5; frame++) {
            currentFrame = frame * 10;
            
            for (int i = 0; i < numEntities; i++) {
                var id = new LongEntityID(i);
                var dynamics = dynamicsMap.get(id);
                
                // Different movement patterns
                var position = new Point3f();
                if (i % 3 == 0) {
                    // Circular motion
                    position.x = i * 50 + 20 * (float)Math.cos(frame * 0.5);
                    position.y = (i % 5) * 20 + 20 * (float)Math.sin(frame * 0.5);
                    position.z = 0;
                } else if (i % 3 == 1) {
                    // Linear motion
                    position.x = i * 50 + frame * 5;
                    position.y = (i % 5) * 20;
                    position.z = 0;
                } else {
                    // Stationary
                    position = entityManager.getEntityPosition(id);
                }
                
                entityManager.updateEntityPosition(id, position);
                dynamics.updatePosition(position, baseTime + frame * 100);
            }
        }

        // Phase 3: Create TBVs based on dynamics
        for (int i = 0; i < numEntities; i++) {
            var id = new LongEntityID(i);
            var dynamics = dynamicsMap.get(id);
            var bounds = entityManager.getEntityBounds(id);
            var velocity = dynamics.getVelocity();
            
            var tbv = new TemporalBoundingVolume(id, bounds, velocity, 
                                               currentFrame, dsocConfig.getTbvStrategy());
            tbvMap.put(id, tbv);
        }

        // Phase 4: Verify TBV quality over time
        for (int futureFrame = currentFrame; futureFrame < currentFrame + 60; futureFrame += 10) {
            var validTBVs = 0;
            var totalQuality = 0.0f;
            
            for (var entry : tbvMap.entrySet()) {
                var tbv = entry.getValue();
                if (tbv.isValid(futureFrame)) {
                    validTBVs++;
                    totalQuality += tbv.getQuality(futureFrame);
                }
            }
            
            // Quality should degrade over time
            if (futureFrame == currentFrame) {
                assertEquals(numEntities, validTBVs);
            } else if (futureFrame > currentFrame + 30) {
                // Average quality should be degrading
                assertTrue(totalQuality / validTBVs < 0.7f, 
                          "Quality should degrade: " + (totalQuality / validTBVs));
            }
        }

        // Phase 5: Identify entities needing TBV refresh
        var refreshFrame = currentFrame + 45;
        var needsRefresh = new ArrayList<LongEntityID>();
        
        for (var entry : tbvMap.entrySet()) {
            var id = entry.getKey();
            var tbv = entry.getValue();
            
            if (!tbv.isValid(refreshFrame) || 
                tbv.getQuality(refreshFrame) < dsocConfig.getTbvRefreshThreshold()) {
                needsRefresh.add(id);
            }
        }
        
        assertFalse(needsRefresh.isEmpty());
        
        // Verify high-velocity entities need refresh sooner
        var highVelocityCount = 0;
        for (var id : needsRefresh) {
            if (dynamicsMap.get(id).getSpeed() > 5.0f) {
                highVelocityCount++;
            }
        }
        assertTrue(highVelocityCount > 0);
    }
}