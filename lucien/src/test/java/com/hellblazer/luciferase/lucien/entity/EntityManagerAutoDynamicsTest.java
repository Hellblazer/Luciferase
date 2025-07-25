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
        
        // Configure auto-dynamics with frame manager
        entityManager.setFrameManager(frameManager);
        entityManager.setAutoDynamicsEnabled(true);
        assertTrue(entityManager.isAutoDynamicsEnabled());
        
        // Update position - should automatically update dynamics
        frameManager.incrementFrame(); // Frame 1
        entityManager.updateEntityPosition(id, new Point3f(10, 0, 0));
        
        frameManager.incrementFrame(); // Frame 2
        entityManager.updateEntityPosition(id, new Point3f(20, 0, 0));
        
        // Check dynamics were updated
        assertEquals(2, dynamics.getHistoryCount());
        var velocity = dynamics.getVelocity();
        assertTrue(velocity.x > 0); // Moving in positive X direction
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
}