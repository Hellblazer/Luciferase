/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FlockingEntityFactory.
 * <p>
 * Verifies:
 * - Creation of flocking entities with default parameters
 * - Correct starting position assignment
 * - Flocking configuration application
 * - Multiple entities have unique IDs
 * - Scaling parameters work correctly
 * <p>
 * Phase 8C Day 1: Behavioral Entity Integration
 *
 * @author hal.hildebrand
 */
class FlockingEntityFactoryTest {

    @Test
    void testCreateEntityWithDefaultParams() {
        // Create entity with default parameters
        var entityId = UUID.randomUUID();
        var location = Tet.ROOT_TET.toTet().child(0).tmIndex();

        var entity = FlockingEntityFactory.createEntity(entityId, location);

        // Verify entity created
        assertNotNull(entity, "Entity should be created");
        assertEquals(entityId, entity.getId(), "Entity should have correct ID");
        assertEquals(location, entity.getLocation(), "Entity should have correct location");

        // Verify default flocking parameters
        var behavior = entity.getFlockingBehavior();
        assertNotNull(behavior, "Entity should have flocking behavior");
        assertEquals(30.0, behavior.getAoiRadius(), 1e-6, "Default AOI radius should be 30.0");
        assertEquals(15.0, behavior.getMaxSpeed(), 1e-6, "Default max speed should be 15.0");
        assertEquals(5.0, behavior.getSeparationRadius(), 1e-6, "Default separation radius should be 5.0");
        assertEquals(1.0, behavior.getMass(), 1e-6, "Default mass should be 1.0");
    }

    @Test
    void testEntityHasCorrectStartingPosition() {
        // Create entity at specific location
        var entityId = UUID.randomUUID();
        var location = Tet.ROOT_TET.toTet().child(3).tmIndex();

        var entity = FlockingEntityFactory.createEntity(entityId, location);

        // Verify location
        assertEquals(location, entity.getLocation(), "Entity should start at specified location");

        // Verify entity has zero initial velocity (starts stationary)
        var velocity = entity.getVelocity();
        assertNotNull(velocity, "Entity should have velocity vector");
        assertEquals(0.0, velocity.x, 1e-6, "Initial velocity x should be 0");
        assertEquals(0.0, velocity.y, 1e-6, "Initial velocity y should be 0");
        assertEquals(0.0, velocity.z, 1e-6, "Initial velocity z should be 0");
    }

    @Test
    void testFlockingConfigAppliesCorrectly() {
        // Create custom configuration
        var config = new FlockingEntityFactory.FlockingConfig();
        config.aoiRadius = 50.0;
        config.maxSpeed = 20.0;
        config.separationRadius = 10.0;
        config.mass = 2.0;

        var entityId = UUID.randomUUID();
        var location = Tet.ROOT_TET.toTet().child(0).tmIndex();

        // Create entity with custom config
        var entity = FlockingEntityFactory.createWithConfig(entityId, location, config);

        // Verify custom parameters applied
        var behavior = entity.getFlockingBehavior();
        assertEquals(50.0, behavior.getAoiRadius(), 1e-6, "AOI radius should match config");
        assertEquals(20.0, behavior.getMaxSpeed(), 1e-6, "Max speed should match config");
        assertEquals(10.0, behavior.getSeparationRadius(), 1e-6, "Separation radius should match config");
        assertEquals(2.0, behavior.getMass(), 1e-6, "Mass should match config");
    }

    @Test
    void testMultipleEntitiesHaveUniqueIds() {
        // Create 100 entities
        var location = Tet.ROOT_TET.toTet().child(0).tmIndex();
        var entities = new HashSet<UUID>();

        for (int i = 0; i < 100; i++) {
            var entityId = UUID.randomUUID();
            var entity = FlockingEntityFactory.createEntity(entityId, location);
            entities.add(entity.getId());
        }

        // Verify all IDs unique
        assertEquals(100, entities.size(), "All 100 entity IDs should be unique");
    }

    @Test
    void testScalingParameters() {
        // Test with high-speed configuration
        var fastConfig = new FlockingEntityFactory.FlockingConfig();
        fastConfig.maxSpeed = 100.0;
        fastConfig.aoiRadius = 100.0;

        var entityId = UUID.randomUUID();
        var location = Tet.ROOT_TET.toTet().child(0).tmIndex();

        var fastEntity = FlockingEntityFactory.createWithConfig(entityId, location, fastConfig);

        // Verify high-speed parameters
        var behavior = fastEntity.getFlockingBehavior();
        assertEquals(100.0, behavior.getMaxSpeed(), 1e-6, "Max speed should be 100.0");
        assertEquals(100.0, behavior.getAoiRadius(), 1e-6, "AOI radius should be 100.0");
    }

    @Test
    void testDefaultConfigValues() {
        // Verify default config has expected values
        var config = new FlockingEntityFactory.FlockingConfig();

        assertEquals(30.0, config.aoiRadius, 1e-6, "Default AOI radius should be 30.0");
        assertEquals(15.0, config.maxSpeed, 1e-6, "Default max speed should be 15.0");
        assertEquals(5.0, config.separationRadius, 1e-6, "Default separation radius should be 5.0");
        assertEquals(1.0, config.mass, 1e-6, "Default mass should be 1.0");
    }

    @Test
    void testCreateMultipleEntitiesAtDifferentLocations() {
        // Create entities at different L1 child locations
        var root = Tet.ROOT_TET.toTet();

        for (int i = 0; i < 8; i++) {
            var entityId = UUID.randomUUID();
            var location = root.child(i).tmIndex();

            var entity = FlockingEntityFactory.createEntity(entityId, location);

            assertNotNull(entity, "Entity " + i + " should be created");
            assertEquals(location, entity.getLocation(), "Entity " + i + " should have correct location");
        }
    }
}
