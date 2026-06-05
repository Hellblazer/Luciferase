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

import com.hellblazer.luciferase.lucien.FrameManager;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test automatic dynamics updates in EntityManager
 *
 * @author hal.hildebrand
 */
public class EntityManagerAutoDynamicsTest {

    private EntityManager<MortonKey, LongEntityID, String> entityManager;
    private FrameManager frameManager;

    @BeforeEach
    void setUp() {
        entityManager = new EntityManager<>(new SequentialLongIDGenerator());
        frameManager = new FrameManager();
    }

    @Test
    void testAutoDynamicsWithFrameManager() {
        // Create entity
        var id = entityManager.generateEntityId();
        var initialPos = new Point3f(0, 0, 0);
        entityManager.createOrUpdateEntity(id, "Test", initialPos, null);

        // Create dynamics explicitly
        var dynamics = entityManager.getOrCreateDynamics(id);
        assertEquals(0, dynamics.getHistoryCount());

        // Configure auto-dynamics with frame manager AND a TestClock so velocity is deterministic.
        // After the clock-injection fix, auto-dynamics always uses clock.currentTimeMillis() (wall-ms),
        // never frame numbers. Without a TestClock the two back-to-back updates could land in the same
        // millisecond → deltaTime=0 → velocity=0.
        var testClock = new TestClock(1_000_000L);
        entityManager.setClock(testClock);
        entityManager.setFrameManager(frameManager);
        entityManager.setAutoDynamicsEnabled(true);
        assertTrue(entityManager.isAutoDynamicsEnabled());

        // Update position - dynamics timestamp now comes from clock, not frameManager
        frameManager.incrementFrame(); // Frame 1 (not used for dynamics time)
        entityManager.updateEntityPosition(id, new Point3f(10, 0, 0));

        testClock.advance(1000L);        // 1000 ms later
        frameManager.incrementFrame();   // Frame 2
        entityManager.updateEntityPosition(id, new Point3f(20, 0, 0));

        // Check dynamics were updated
        assertEquals(2, dynamics.getHistoryCount());
        var velocity = dynamics.getVelocity();
        assertTrue(velocity.x > 0, "Moving in positive X direction"); // Moving in positive X direction
        assertEquals(0, velocity.y, 0.001f);
        assertEquals(0, velocity.z, 0.001f);
    }
    
    @Test
    void testAutoDynamicsWithSystemTime() {
        // Create entity
        var id = entityManager.generateEntityId();
        var initialPos = new Point3f(0, 0, 0);
        entityManager.createOrUpdateEntity(id, "Test", initialPos, null);
        
        // Create dynamics
        var dynamics = entityManager.getOrCreateDynamics(id);
        
        // Enable auto-dynamics without frame manager (uses System time)
        entityManager.setAutoDynamicsEnabled(true);
        
        // Update positions with small delays
        entityManager.updateEntityPosition(id, new Point3f(5, 0, 0));
        
        try {
            Thread.sleep(10); // Small delay
        } catch (InterruptedException e) {
            // Ignore
        }
        
        entityManager.updateEntityPosition(id, new Point3f(10, 0, 0));
        
        // Check dynamics were updated
        assertEquals(2, dynamics.getHistoryCount());
        assertTrue(dynamics.getVelocity().x > 0);
    }
    
    @Test
    void testDisabledAutoDynamics() {
        // Create entity
        var id = entityManager.generateEntityId();
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);
        
        // Create dynamics
        var dynamics = entityManager.getOrCreateDynamics(id);
        dynamics.updatePosition(new Point3f(0, 0, 0), 1000);
        assertEquals(1, dynamics.getHistoryCount());
        
        // Auto-dynamics is disabled by default
        assertFalse(entityManager.isAutoDynamicsEnabled());
        
        // Update position - should NOT update dynamics
        entityManager.updateEntityPosition(id, new Point3f(10, 0, 0));
        
        // Dynamics should be unchanged
        assertEquals(1, dynamics.getHistoryCount());
    }
    
    @Test
    void testCreateOrUpdateWithAutoDynamics() {
        entityManager.setAutoDynamicsEnabled(true);
        entityManager.setFrameManager(frameManager);
        
        var id = entityManager.generateEntityId();
        
        // Initial creation - should not update dynamics (entity is new)
        frameManager.incrementFrame();
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);
        assertFalse(entityManager.hasDynamics(id)); // No dynamics created yet
        
        // Create dynamics manually
        var dynamics = entityManager.getOrCreateDynamics(id);

        // Update via createOrUpdate - should update dynamics (entity exists)
        frameManager.incrementFrame();
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(10, 0, 0), null);

        assertEquals(1, dynamics.getHistoryCount());
    }

    /**
     * Regression guard for the frame-counter vs. wall-clock time-base bug.
     * <p>
     * EntityDynamics.calculateVelocity divides deltaTime by 1000 to convert ms → seconds.
     * If frame numbers (small ints) were fed instead of ms the deltaTime would be ~0.001 ms,
     * inflating velocity by a factor of ~1 000 000x.
     * <p>
     * With a TestClock we advance by exactly 1000 ms per step, which must produce
     * velocity ≈ 10 units/s (10 spatial units / 1 second), not ~10 000 000 units/s.
     */
    @Test
    void testClockInjection_deterministicVelocity() {
        var testClock = new TestClock(1_000_000L); // epoch-like ms base
        entityManager.setClock(testClock);
        entityManager.setAutoDynamicsEnabled(true);

        var id = entityManager.generateEntityId();
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);
        var dynamics = entityManager.getOrCreateDynamics(id);

        // t=1_000_000 ms: first real position sample
        entityManager.updateEntityPosition(id, new Point3f(0, 0, 0));

        // advance 1000 ms, move 10 units → expected velocity = 10 units/s
        testClock.advance(1000L);
        entityManager.updateEntityPosition(id, new Point3f(10, 0, 0));

        assertEquals(2, dynamics.getHistoryCount());
        var velocity = dynamics.getVelocity();

        // Must be ~10 units/s, NOT ~10 000 000 units/s (the frame-counter corruption)
        assertEquals(10.0f, velocity.x, 0.5f,
                     "velocity must reflect ms time base (10 units/1 s), got: " + velocity.x);
        assertEquals(0.0f, velocity.y, 0.01f);
        assertEquals(0.0f, velocity.z, 0.01f);
    }

    /**
     * Same regression guard via createOrUpdateEntity path (auto-dynamics on existing entity).
     */
    @Test
    void testClockInjection_createOrUpdate_deterministicVelocity() {
        var testClock = new TestClock(2_000_000L);
        entityManager.setClock(testClock);
        entityManager.setAutoDynamicsEnabled(true);

        var id = entityManager.generateEntityId();
        // First call is "new", so dynamics won't be updated yet
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);
        var dynamics = entityManager.getOrCreateDynamics(id);

        // Seed first position explicitly (entity already exists now)
        testClock.advance(0L); // t=2_000_000
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(0, 0, 0), null);
        assertEquals(1, dynamics.getHistoryCount());

        // advance 500 ms, move 5 units → expected velocity = 10 units/s
        testClock.advance(500L);
        entityManager.createOrUpdateEntity(id, "Test", new Point3f(5, 0, 0), null);
        assertEquals(2, dynamics.getHistoryCount());

        var velocity = dynamics.getVelocity();
        assertEquals(10.0f, velocity.x, 0.5f,
                     "createOrUpdate velocity must reflect ms time base (5 units/0.5 s), got: " + velocity.x);
    }

    /**
     * Confirm that both the frameManager-present and frameManager-absent auto-dynamics paths
     * now call the same clock (no mixed time base).  With a FrameManager configured the old
     * code took the frame-number branch; the new code ignores frameManager for dynamics and
     * always uses clock.currentTimeMillis().
     */
    @Test
    void testSameTimeBaseWithOrWithoutFrameManager() {
        var testClock = new TestClock(5_000_000L);

        // --- branch A: with FrameManager ---
        var emWithFM = new EntityManager<MortonKey, LongEntityID, String>(new SequentialLongIDGenerator());
        emWithFM.setClock(testClock);
        emWithFM.setAutoDynamicsEnabled(true);
        emWithFM.setFrameManager(new FrameManager()); // frameManager present

        var idA = emWithFM.generateEntityId();
        emWithFM.createOrUpdateEntity(idA, "A", new Point3f(0, 0, 0), null);
        var dynA = emWithFM.getOrCreateDynamics(idA);
        emWithFM.updateEntityPosition(idA, new Point3f(0, 0, 0));
        testClock.advance(1000L);
        emWithFM.updateEntityPosition(idA, new Point3f(10, 0, 0));
        var velA = dynA.getVelocity();

        // --- branch B: without FrameManager ---
        testClock.setTime(5_000_000L); // reset
        var emNoFM = new EntityManager<MortonKey, LongEntityID, String>(new SequentialLongIDGenerator());
        emNoFM.setClock(testClock);
        emNoFM.setAutoDynamicsEnabled(true);
        // no frameManager set

        var idB = emNoFM.generateEntityId();
        emNoFM.createOrUpdateEntity(idB, "B", new Point3f(0, 0, 0), null);
        var dynB = emNoFM.getOrCreateDynamics(idB);
        emNoFM.updateEntityPosition(idB, new Point3f(0, 0, 0));
        testClock.advance(1000L);
        emNoFM.updateEntityPosition(idB, new Point3f(10, 0, 0));
        var velB = dynB.getVelocity();

        // Both must produce the same velocity — consistent ms time base
        assertEquals(velA.x, velB.x, 0.5f,
                     "With and without FrameManager must produce the same velocity; with=" + velA.x + " without=" + velB.x);
        assertEquals(10.0f, velA.x, 0.5f, "velocity should be ~10 units/s");
    }
}