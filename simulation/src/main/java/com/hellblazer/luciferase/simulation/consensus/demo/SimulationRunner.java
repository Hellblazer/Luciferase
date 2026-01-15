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
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulation runner coordinating entity movements and consensus-based migrations.
 * <p>
 * Responsibilities:
 * - Main simulation tick loop
 * - Update entity positions based on flocking behavior
 * - Detect boundary crossings
 * - Trigger consensus for cross-bubble migrations
 * - Track entity ghost states
 * - Monitor simulation health (100% retention over 100 ticks)
 * <p>
 * SIMULATION LOOP:
 * For each tick:
 * 1. Get all entities in each bubble
 * 2. Calculate neighbor lists (AOI radius 30)
 * 3. Update velocities (separation, alignment, cohesion)
 * 4. Update positions (velocity * delta_time)
 * 5. Detect boundary crossings
 * 6. If entity crossed bubble boundary:
 * - Create MigrationProposal via ConsensusAwareMigrator
 * - Wait for approval
 * - Move entity to new bubble (or ghost state)
 * 7. Check convergence (all views stable)
 * <p>
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for entity tracking and thread-safe metrics.
 * <p>
 * Phase 8C Day 1: Behavioral Entity Integration
 *
 * @author hal.hildebrand
 */
public class SimulationRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);
    private static final float DELTA_TIME = 0.1f; // Time step per tick (seconds)

    private final ConsensusBubbleGrid grid;
    private final EntitySpawner spawner;
    private final ConsensusAwareMigrator migrator;

    /**
     * Entity ID to FlockingEntity mapping
     */
    private final ConcurrentHashMap<UUID, FlockingEntity> entities = new ConcurrentHashMap<>();

    /**
     * Metrics
     */
    private final AtomicLong boundaryCrossings = new AtomicLong(0);
    private final AtomicLong consensusMigrations = new AtomicLong(0);
    private final AtomicInteger currentTick = new AtomicInteger(0);

    /**
     * Create SimulationRunner.
     *
     * @param grid     ConsensusBubbleGrid for topology
     * @param spawner  EntitySpawner for entity management
     * @param migrator ConsensusAwareMigrator for cross-bubble migrations
     */
    public SimulationRunner(ConsensusBubbleGrid grid, EntitySpawner spawner, ConsensusAwareMigrator migrator) {
        this.grid = Objects.requireNonNull(grid, "grid must not be null");
        this.spawner = Objects.requireNonNull(spawner, "spawner must not be null");
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");

        // Don't initialize entities here - they will be loaded lazily on first tick
        // This allows tests to spawn entities after creating the runner
    }

    /**
     * Run simulation for specified number of ticks.
     *
     * @param tickCount Number of ticks to run
     */
    public void runSimulation(int tickCount) {
        if (tickCount <= 0) {
            throw new IllegalArgumentException("Tick count must be positive, got " + tickCount);
        }

        log.debug("Running simulation for {} ticks", tickCount);

        for (int i = 0; i < tickCount; i++) {
            simulationTick();
        }

        log.info("Completed {} ticks. Total ticks: {}, entities: {}, crossings: {}, migrations: {}",
                tickCount, currentTick.get(), entities.size(), boundaryCrossings.get(), consensusMigrations.get());
    }

    /**
     * Run simulation until convergence or max ticks.
     * <p>
     * Convergence: All entities have stable positions (velocity below threshold).
     *
     * @param maxTicks Maximum ticks to run
     */
    public void runUntilConverged(int maxTicks) {
        log.debug("Running simulation until convergence (max {} ticks)", maxTicks);

        for (int i = 0; i < maxTicks; i++) {
            simulationTick();

            if (hasConverged()) {
                log.info("Simulation converged at tick {}", currentTick.get());
                return;
            }
        }

        log.info("Simulation reached max ticks ({}) without convergence", maxTicks);
    }

    /**
     * Get total entity count.
     * <p>
     * Includes entities from spawner that haven't been loaded yet.
     *
     * @return Entity count
     */
    public int getEntityCount() {
        // Lazy load entities if needed
        if (entities.isEmpty() && spawner.getEntityCount() > 0) {
            return spawner.getEntityCount();
        }
        return entities.size();
    }

    /**
     * Get total boundary crossings detected.
     *
     * @return Boundary crossing count
     */
    public long getTotalBoundaryCrossings() {
        return boundaryCrossings.get();
    }

    /**
     * Get total consensus migrations executed.
     *
     * @return Consensus migration count
     */
    public long getConsensusMigrations() {
        return consensusMigrations.get();
    }

    /**
     * Get average entity speed.
     *
     * @return Average speed (units/tick)
     */
    public double getAverageEntitySpeed() {
        if (entities.isEmpty()) {
            return 0.0;
        }

        var totalSpeed = entities.values().stream()
            .mapToDouble(e -> e.getVelocity().length())
            .sum();

        return totalSpeed / entities.size();
    }

    @Override
    public void close() {
        log.info("Closing SimulationRunner. Final stats - Ticks: {}, Entities: {}, Crossings: {}, Migrations: {}",
                currentTick.get(), entities.size(), boundaryCrossings.get(), consensusMigrations.get());
        // Release resources if needed
    }

    /**
     * Initialize entities from spawner's entity list.
     */
    private void initializeEntitiesFromSpawner() {
        var allEntityIds = spawner.getAllEntities();

        for (var entityId : allEntityIds) {
            var bubbleOpt = spawner.getEntityBubble(entityId);
            if (bubbleOpt.isPresent()) {
                var bubbleIndex = bubbleOpt.get();
                var bubble = grid.getBubble(bubbleIndex);
                var location = bubble.getTetrahedra()[0]; // Default to first tet

                var entity = FlockingEntityFactory.createEntity(entityId, location);
                entities.put(entityId, entity);
            }
        }

        log.debug("Initialized {} entities from spawner", entities.size());
    }

    /**
     * Execute one simulation tick.
     */
    private void simulationTick() {
        // Lazy initialization: load entities from spawner on first tick
        if (entities.isEmpty() && spawner.getEntityCount() > 0) {
            initializeEntitiesFromSpawner();
        }

        currentTick.incrementAndGet();

        // Update velocities based on flocking behavior
        updateVelocities();

        // Update positions based on velocities
        updatePositions();

        // Detect and handle boundary crossings
        detectBoundaryCrossings();
    }

    /**
     * Update entity velocities using flocking algorithm.
     * <p>
     * For each entity:
     * 1. Find neighbors within AOI radius
     * 2. Calculate separation, alignment, cohesion forces
     * 3. Sum forces and update velocity
     * 4. Clamp velocity to max speed
     */
    private void updateVelocities() {
        // For each entity, calculate flocking forces
        for (var entity : entities.values()) {
            var neighbors = findNeighbors(entity);

            var separation = calculateSeparation(entity, neighbors);
            var alignment = calculateAlignment(entity, neighbors);
            var cohesion = calculateCohesion(entity, neighbors);

            // Sum forces (equal weighting for now)
            var totalForce = new Vector3f();
            totalForce.add(separation);
            totalForce.add(alignment);
            totalForce.add(cohesion);

            // Update velocity
            var newVelocity = new Vector3f(entity.getVelocity());
            newVelocity.add(totalForce);

            // Clamp to max speed
            var behavior = entity.getFlockingBehavior();
            var speed = newVelocity.length();
            if (speed > behavior.getMaxSpeed()) {
                newVelocity.scale((float) (behavior.getMaxSpeed() / speed));
            }

            entity.setVelocity(newVelocity);
        }
    }

    /**
     * Update entity positions based on velocities.
     */
    private void updatePositions() {
        for (var entity : entities.values()) {
            var velocity = entity.getVelocity();
            var displacement = new Vector3f(velocity);
            displacement.scale(DELTA_TIME);

            // Update position (simplified - just keep same TetreeKey for now)
            // In full implementation, would compute new TetreeKey based on displacement
        }
    }

    /**
     * Detect entities crossing bubble boundaries.
     */
    private void detectBoundaryCrossings() {
        // Simplified: No boundary crossings in initial implementation
        // Full implementation would check entity position against bubble boundaries
    }

    /**
     * Find neighbors within AOI radius.
     *
     * @param entity Target entity
     * @return List of neighboring entities
     */
    private List<FlockingEntity> findNeighbors(FlockingEntity entity) {
        var neighbors = new ArrayList<FlockingEntity>();
        var aoiRadius = entity.getFlockingBehavior().getAoiRadius();

        // Simplified: All entities in same bubble are potential neighbors
        // Full implementation would use spatial index for efficient neighbor search
        for (var other : entities.values()) {
            if (other.equals(entity)) {
                continue;
            }

            // For now, assume all entities are neighbors (simplified)
            neighbors.add(other);
        }

        return neighbors;
    }

    /**
     * Calculate separation force.
     * <p>
     * Steer away from neighbors within separation radius.
     *
     * @param entity    Target entity
     * @param neighbors Nearby entities
     * @return Separation force vector
     */
    private Vector3f calculateSeparation(FlockingEntity entity, List<FlockingEntity> neighbors) {
        var force = new Vector3f(0, 0, 0);
        var separationRadius = entity.getFlockingBehavior().getSeparationRadius();

        // Simplified: No separation force in initial implementation
        return force;
    }

    /**
     * Calculate alignment force.
     * <p>
     * Steer towards average heading of neighbors.
     *
     * @param entity    Target entity
     * @param neighbors Nearby entities
     * @return Alignment force vector
     */
    private Vector3f calculateAlignment(FlockingEntity entity, List<FlockingEntity> neighbors) {
        var force = new Vector3f(0, 0, 0);

        if (neighbors.isEmpty()) {
            return force;
        }

        // Average velocity of neighbors
        var avgVelocity = new Vector3f();
        for (var neighbor : neighbors) {
            avgVelocity.add(neighbor.getVelocity());
        }
        avgVelocity.scale(1.0f / neighbors.size());

        // Steer towards average velocity
        force.sub(avgVelocity, entity.getVelocity());

        return force;
    }

    /**
     * Calculate cohesion force.
     * <p>
     * Steer towards average position of neighbors.
     *
     * @param entity    Target entity
     * @param neighbors Nearby entities
     * @return Cohesion force vector
     */
    private Vector3f calculateCohesion(FlockingEntity entity, List<FlockingEntity> neighbors) {
        var force = new Vector3f(0, 0, 0);

        // Simplified: No cohesion force in initial implementation
        return force;
    }

    /**
     * Check if simulation has converged.
     * <p>
     * Convergence: All entities have velocity magnitude below threshold.
     *
     * @return true if converged
     */
    private boolean hasConverged() {
        var threshold = 0.1; // Velocity threshold for convergence

        return entities.values().stream()
            .allMatch(e -> e.getVelocity().length() < threshold);
    }
}
