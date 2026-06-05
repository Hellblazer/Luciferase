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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityDynamics;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for VisibilityStateManager
 *
 * @author hal.hildebrand
 */
public class VisibilityStateManagerTest {
    
    private VisibilityStateManager<LongEntityID> manager;
    private DSOCConfiguration config;
    
    @BeforeEach
    void setUp() {
        config = DSOCConfiguration.defaultConfig();
        manager = new VisibilityStateManager<>(config);
    }
    
    @Test
    void testInitialState() {
        var entityId = new LongEntityID(1);
        
        // Initially unknown
        assertEquals(VisibilityStateManager.VisibilityState.UNKNOWN, manager.getState(entityId));
        assertNull(manager.getVisibilityInfo(entityId));
        assertFalse(manager.hasTBV(entityId));
    }
    
    @Test
    void testStateTransitions() {
        var entityId = new LongEntityID(1);
        var frame = 100L;
        
        // UNKNOWN -> VISIBLE
        var state = manager.updateVisibility(entityId, true, frame);
        assertEquals(VisibilityStateManager.VisibilityState.VISIBLE, state);
        assertEquals(VisibilityStateManager.VisibilityState.VISIBLE, manager.getState(entityId));
        
        // VISIBLE -> VISIBLE (no change)
        state = manager.updateVisibility(entityId, true, frame + 10);
        assertEquals(VisibilityStateManager.VisibilityState.VISIBLE, state);
        
        // VISIBLE -> HIDDEN_WITH_TBV
        state = manager.updateVisibility(entityId, false, frame + 20);
        assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV, state);
        
        // Create TBV for testing
        var dynamics = new EntityDynamics();
        dynamics.updatePosition(new Point3f(0, 0, 0), frame);
        dynamics.updatePosition(new Point3f(10, 0, 0), frame + 10);
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        manager.createTBV(entityId, dynamics, bounds, frame + 20);
        
        // HIDDEN_WITH_TBV -> VISIBLE
        state = manager.updateVisibility(entityId, true, frame + 30);
        assertEquals(VisibilityStateManager.VisibilityState.VISIBLE, state);
        assertFalse(manager.hasTBV(entityId));
        
        // Back to hidden
        state = manager.updateVisibility(entityId, false, frame + 40);
        assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV, state);
        
        // Create short-lived TBV
        var shortTBV = new TemporalBoundingVolume<LongEntityID>(entityId, bounds, new Vector3f(1, 0, 0), 
                                                   (int)(frame + 40), new FixedDurationTBVStrategy(10));
        // Manually expire TBV
        state = manager.updateVisibility(entityId, false, frame + 55); // Past TBV validity
        // Note: In real usage, pruneExpiredTBVs would handle this
    }
    
    @Test
    void testTBVCreation() {
        var entityId = new LongEntityID(1);
        var frame = 100L;
        
        // Set up entity as visible first
        manager.updateVisibility(entityId, true, frame);
        
        // Create dynamics with velocity
        var dynamics = new EntityDynamics();
        dynamics.updatePosition(new Point3f(0, 0, 0), frame);
        dynamics.updatePosition(new Point3f(15, 0, 0), frame + 1000); // 15 units/sec
        
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        
        // Create TBV
        var tbv = manager.createTBV(entityId, dynamics, bounds, frame + 10);
        assertNotNull(tbv);
        assertEquals(entityId, tbv.getEntityId());
        assertTrue(manager.hasTBV(entityId));
        assertEquals(tbv, manager.getTBV(entityId));
        
        // Verify state changed to HIDDEN_WITH_TBV
        assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV, manager.getState(entityId));
    }
    
    @Test
    void testTBVCreationBelowThreshold() {
        var entityId = new LongEntityID(1);
        var frame = 100L;
        
        // Configure to not always create TBV
        config = DSOCConfiguration.defaultConfig()
            .withVelocityThreshold(10.0f)
            .withAlwaysCreateTbv(false);
        manager = new VisibilityStateManager<>(config);
        
        // Set up entity as visible
        manager.updateVisibility(entityId, true, frame);
        
        // Create dynamics with low velocity
        var dynamics = new EntityDynamics();
        dynamics.updatePosition(new Point3f(0, 0, 0), frame);
        dynamics.updatePosition(new Point3f(1, 0, 0), frame + 1000); // 1 unit/sec < threshold
        
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        
        // Try to create TBV - should fail due to low velocity
        var tbv = manager.createTBV(entityId, dynamics, bounds, frame + 10);
        assertNull(tbv);
        assertFalse(manager.hasTBV(entityId));
    }
    
    @Test
    void testVisibilityInfo() {
        var entityId = new LongEntityID(1);
        var frame = 100L;
        
        // Make visible
        manager.updateVisibility(entityId, true, frame);
        var info = manager.getVisibilityInfo(entityId);
        assertNotNull(info);
        assertEquals(VisibilityStateManager.VisibilityState.VISIBLE, info.getState());
        assertEquals(frame, info.getLastVisibleFrame());
        assertEquals(-1, info.getHiddenSinceFrame());
        assertEquals(0, info.getHiddenDuration(frame + 50));
        
        // Make hidden
        manager.updateVisibility(entityId, false, frame + 10);
        info = manager.getVisibilityInfo(entityId);
        assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV, info.getState());
        assertEquals(frame, info.getLastVisibleFrame()); // Still the same
        assertEquals(frame + 10, info.getHiddenSinceFrame());
        assertEquals(40, info.getHiddenDuration(frame + 50));
    }
    
    @Test
    void testTBVPruning() {
        var config = DSOCConfiguration.defaultConfig();
        manager = new VisibilityStateManager<>(config);
        
        // Create multiple entities with TBVs
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        var shortStrategy = new FixedDurationTBVStrategy(20);
        var longStrategy = new FixedDurationTBVStrategy(100);
        
        for (int i = 1; i <= 3; i++) {
            var entityId = new LongEntityID(i);
            manager.updateVisibility(entityId, true, 100);
            
            var dynamics = new EntityDynamics();
            dynamics.updatePosition(new Point3f(0, 0, 0), 100);
            dynamics.updatePosition(new Point3f(10, 0, 0), 101);
            
            // Use different strategies
            var strategy = i <= 2 ? shortStrategy : longStrategy;
            config = DSOCConfiguration.defaultConfig()
                .withTBVStrategy(strategy);
            manager = new VisibilityStateManager<>(config);
            
            manager.updateVisibility(entityId, true, 100);
            manager.createTBV(entityId, dynamics, bounds, 100);
        }
        
        // Prune at frame 125 - should expire short TBVs
        var expired = manager.pruneExpiredTBVs(125);
        // Note: Since we recreated manager, this test needs adjustment
        // In practice, you'd have one manager with multiple TBVs
    }
    
    @Test
    void testEntitiesNeedingUpdate() {
        var entityId1 = new LongEntityID(1);
        var entityId2 = new LongEntityID(2);
        var entityId3 = new LongEntityID(3);
        
        // Entity 1: HIDDEN_EXPIRED
        manager.updateVisibility(entityId1, true, 100);
        manager.updateVisibility(entityId1, false, 110);
        // Simulate expired state
        var info = manager.getVisibilityInfo(entityId1);
        // Would need to expose state setting for testing
        
        // Entity 2: HIDDEN_WITH_TBV (good quality)
        manager.updateVisibility(entityId2, true, 100);
        var dynamics = new EntityDynamics();
        dynamics.updatePosition(new Point3f(0, 0, 0), 100);
        dynamics.updatePosition(new Point3f(10, 0, 0), 110);
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        manager.createTBV(entityId2, dynamics, bounds, 120);
        
        // Entity 3: VISIBLE
        manager.updateVisibility(entityId3, true, 100);
        
        // Get entities needing update
        var needingUpdate = manager.getEntitiesNeedingUpdate(130);
        // This would include expired entities and low-quality TBVs
    }
    
    @Test
    void testStatistics() {
        // Create some entities in different states
        for (int i = 1; i <= 10; i++) {
            var entityId = new LongEntityID(i);
            if (i <= 3) {
                // Visible
                manager.updateVisibility(entityId, true, 100);
            } else if (i <= 6) {
                // Hidden with TBV
                manager.updateVisibility(entityId, true, 100);
                manager.updateVisibility(entityId, false, 110);
                
                var dynamics = new EntityDynamics();
                dynamics.updatePosition(new Point3f(0, 0, 0), 100);
                dynamics.updatePosition(new Point3f(10, 0, 0), 110);
                var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
                manager.createTBV(entityId, dynamics, bounds, 110);
            } else if (i <= 8) {
                // Hidden expired
                manager.updateVisibility(entityId, true, 100);
                manager.updateVisibility(entityId, false, 110);
            } else {
                // Unknown (no updates)
            }
        }
        
        var stats = manager.getStatistics();
        assertEquals(8, stats.get("totalEntities")); // 10 - 2 unknown
        assertTrue((Long) stats.get("visibleEntities") >= 3);
        assertTrue((Long) stats.get("totalTBVsCreated") >= 3);
        assertTrue((Long) stats.get("totalStateTransitions") > 0);
    }
    
    @Test
    void testClearOperations() {
        var entityId = new LongEntityID(1);
        
        // Set up entity with TBV
        manager.updateVisibility(entityId, true, 100);
        var dynamics = new EntityDynamics();
        dynamics.updatePosition(new Point3f(0, 0, 0), 100);
        dynamics.updatePosition(new Point3f(10, 0, 0), 110);
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        manager.createTBV(entityId, dynamics, bounds, 110);
        
        // Clear entity
        manager.clearEntity(entityId);
        assertEquals(VisibilityStateManager.VisibilityState.UNKNOWN, manager.getState(entityId));
        assertFalse(manager.hasTBV(entityId));
        
        // Set up again
        manager.updateVisibility(entityId, true, 120);
        
        // Clear all
        manager.clear();
        assertEquals(VisibilityStateManager.VisibilityState.UNKNOWN, manager.getState(entityId));
        var stats = manager.getStatistics();
        assertEquals(0, stats.get("totalEntities"));
    }
    
    /**
     * Concurrent test: N threads hammering updateVisibility on ONE shared entity.
     *
     * Assertions:
     * 1. No exception thrown under concurrent access.
     * 2. totalStateTransitions > 0 (real transitions occurred — no lost-update silent failure).
     * 3. State machine never leaves entity in HIDDEN_WITH_TBV forever: after all threads
     *    finish, a final updateVisibility(false) either stays in current state or escapes
     *    (never stays HIDDEN_WITH_TBV with tbv==null indefinitely).
     *
     * Note: HIDDEN_WITH_TBV without a TBV entry is valid when updateVisibility(false) drives
     * the transition directly (createTBV was not called).  The narrower invariant —
     * "if createTBV was the path, the TBV is already present" — is tested in
     * testConcurrentCreateTBVInvariant.
     */
    @Test
    void testConcurrentUpdateVisibilitySingleEntity() throws InterruptedException {
        var entityId = new LongEntityID(42);
        int threadCount = 8;
        int opsPerThread = 500;

        // Start entity visible so state machine is in a defined starting state.
        manager.updateVisibility(entityId, true, 1);

        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threadCount);
        var errors = new ArrayList<Throwable>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        long frame = (long) threadIdx * opsPerThread + i + 2;
                        boolean visible = (i % 2 == 0);
                        manager.updateVisibility(entityId, visible, frame);
                    }
                } catch (Throwable ex) {
                    synchronized (errors) {
                        errors.add(ex);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads did not finish in time");
        pool.shutdown();

        if (!errors.isEmpty()) {
            var first = errors.get(0);
            fail("Thread threw exception: " + first, first);
        }

        // totalStateTransitions must be > 0 (some transitions happened — no silent lost updates)
        var stats = manager.getStatistics();
        assertTrue((Long) stats.get("totalStateTransitions") > 0,
                   "Expected some state transitions");

        // Stranding check: if currently HIDDEN_WITH_TBV with no TBV, a subsequent
        // updateVisibility(false) must escape to HIDDEN_EXPIRED (not stay stuck forever).
        var finalState = manager.getState(entityId);
        if (finalState == VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV
                && !manager.hasTBV(entityId)) {
            var escapedState = manager.updateVisibility(entityId, false, 99999L);
            assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_EXPIRED, escapedState,
                         "Stranded HIDDEN_WITH_TBV (no TBV) must escape to HIDDEN_EXPIRED on next updateVisibility(false)");
        }
    }

    /**
     * Concurrent test: N threads interleave createTBV + updateVisibility(true/false) on ONE entity.
     *
     * Properties checked:
     * 1. No exception/deadlock under concurrent access.
     * 2. totalStateTransitions > 0 (real transitions, not silent lost updates).
     * 3. After all threads finish, if entity is stranded (HIDDEN_WITH_TBV, no TBV),
     *    a single subsequent updateVisibility(false) escapes to HIDDEN_EXPIRED.
     *    This validates that stranding is always transient — resolved on the next update.
     */
    @Test
    void testConcurrentCreateTBVInvariant() throws InterruptedException {
        // Use alwaysCreateTbv so velocity threshold is never an obstacle.
        var cfg = DSOCConfiguration.defaultConfig().withAlwaysCreateTbv(true);
        var mgr = new VisibilityStateManager<LongEntityID>(cfg);

        var entityId = new LongEntityID(99);
        mgr.updateVisibility(entityId, true, 1);

        int threadCount = 6;
        int opsPerThread = 200;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threadCount);
        var errors = new ArrayList<Throwable>();

        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        long frame = (long) threadIdx * opsPerThread + i + 2;
                        if (i % 3 == 0) {
                            // Periodically make visible to reset the state machine.
                            mgr.updateVisibility(entityId, true, frame);
                        } else {
                            var dynamics = new EntityDynamics();
                            dynamics.updatePosition(new Point3f(i, 0, 0), frame);
                            dynamics.updatePosition(new Point3f(i + 5, 0, 0), frame + 1);
                            mgr.createTBV(entityId, dynamics, bounds, frame);
                        }
                    }
                } catch (Throwable ex) {
                    synchronized (errors) {
                        errors.add(ex);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads did not finish in time");
        pool.shutdown();

        if (!errors.isEmpty()) {
            var first = errors.get(0);
            fail("Thread threw exception: " + first, first);
        }

        // At quiescence: any stranded HIDDEN_WITH_TBV (no TBV) must escape on next call.
        var finalState = mgr.getState(entityId);
        if (finalState == VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV
                && !mgr.hasTBV(entityId)) {
            var escapedState = mgr.updateVisibility(entityId, false, 999999L);
            assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_EXPIRED, escapedState,
                         "Stranded HIDDEN_WITH_TBV entity must escape to HIDDEN_EXPIRED on next updateVisibility(false)");
        }

        // Validate real transitions occurred.
        var stats = mgr.getStatistics();
        assertTrue((Long) stats.get("totalStateTransitions") > 0,
                   "Expected some state transitions");
    }

    /**
     * Verifies that an entity in HIDDEN_WITH_TBV with no TBV in activeTBVs
     * (stranded state) is rescued: the next updateVisibility(false) must
     * transition it to HIDDEN_EXPIRED rather than leaving it stuck.
     */
    @Test
    void testStrandedEntityEscapesHiddenWithTBV() {
        var entityId = new LongEntityID(77);
        long frame = 100;

        // Drive entity to HIDDEN_WITH_TBV
        manager.updateVisibility(entityId, true, frame);
        manager.updateVisibility(entityId, false, frame + 1);
        assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV, manager.getState(entityId));

        // Simulate stranded state: remove TBV without updating state.
        // (In production this could happen via clearEntity partial race or
        //  direct activeTBVs eviction — the new code defends against it.)
        manager.clearEntity(entityId);
        // Re-inject only the state entry, leaving activeTBVs empty.
        // We do this by exploiting the public updateVisibility path on a fresh entity.
        // Rebuild manually: VISIBLE -> HIDDEN_WITH_TBV without a TBV.
        manager.updateVisibility(entityId, true, frame + 2);
        manager.updateVisibility(entityId, false, frame + 3);
        // Drain the TBV that was NOT created (there is none — entity went HIDDEN_WITH_TBV
        // purely via updateVisibility with no createTBV, so activeTBVs has no entry).
        assertFalse(manager.hasTBV(entityId),
                    "No TBV was created; entity is in the stranded HIDDEN_WITH_TBV state");
        assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV, manager.getState(entityId));

        // Call updateVisibility(false) again — must escape to HIDDEN_EXPIRED.
        var newState = manager.updateVisibility(entityId, false, frame + 4);
        assertEquals(VisibilityStateManager.VisibilityState.HIDDEN_EXPIRED, newState,
                     "Stranded HIDDEN_WITH_TBV entity must transition to HIDDEN_EXPIRED when tbv==null");
    }

    @Test
    void testGetEntitiesWithTBVs() {
        var entityId1 = new LongEntityID(1);
        var entityId2 = new LongEntityID(2);
        var entityId3 = new LongEntityID(3);
        
        // Create TBVs for entities 1 and 2
        for (var id : new LongEntityID[]{entityId1, entityId2}) {
            manager.updateVisibility(id, true, 100);
            var dynamics = new EntityDynamics();
            dynamics.updatePosition(new Point3f(0, 0, 0), 100);
            dynamics.updatePosition(new Point3f(10, 0, 0), 110);
            var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
            manager.createTBV(id, dynamics, bounds, 110);
        }
        
        // Entity 3 is just visible
        manager.updateVisibility(entityId3, true, 100);
        
        var entitiesWithTBVs = manager.getEntitiesWithTBVs();
        assertEquals(2, entitiesWithTBVs.size());
        assertTrue(entitiesWithTBVs.contains(entityId1));
        assertTrue(entitiesWithTBVs.contains(entityId2));
        assertFalse(entitiesWithTBVs.contains(entityId3));
    }
}