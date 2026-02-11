/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;
import com.hellblazer.luciferase.simulation.ghost.DuplicateDetectionConfig;
import com.hellblazer.luciferase.simulation.ghost.DuplicateEntityDetector;
import com.hellblazer.luciferase.simulation.ghost.MigrationLog;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main simulation orchestrator for multi-bubble tetrahedral environment.
 * <p>
 * <b>Orchestrator Pattern</b>: This class follows the orchestrator pattern,
 * delegating all business logic to specialized component managers. It maintains
 * NO business logic itself - only coordination and lifecycle management.
 * <p>
 * <b>Component Architecture</b>:
 * <ul>
 *   <li>{@link BubbleGridOrchestrator} - Grid and spatial index management</li>
 *   <li>{@link SimulationExecutionEngine} - Tick loop and scheduling</li>
 *   <li>{@link EntityPopulationManager} - Entity creation and distribution</li>
 *   <li>{@link EntityPhysicsManager} - Physics and velocity updates</li>
 *   <li>{@link SimulationQueryService} - Read-only query operations</li>
 *   <li>{@link TetrahedralMigration} - Entity migration (Phase 5D)</li>
 *   <li>{@link DuplicateEntityDetector} - Duplicate detection (Phase 5E)</li>
 * </ul>
 * <p>
 * <b>Tick Loop Coordination (60fps)</b>:
 * <ol>
 *   <li>Physics updates via {@link EntityPhysicsManager}</li>
 *   <li>Migration detection via {@link TetrahedralMigration}</li>
 *   <li>Ghost synchronization via {@link TetreeGhostSyncAdapter}</li>
 *   <li>Duplicate reconciliation via {@link DuplicateEntityDetector}</li>
 *   <li>Metrics recording via {@link SimulationMetrics}</li>
 * </ol>
 * <p>
 * <b>Refactoring History</b>: Decomposed from 558 LOC god class to orchestrator
 * facade (Sprint B B1, January 2026). Original mixed 7-10 responsibilities; now
 * delegates to 7 focused components following Single Responsibility Principle.
 *
 * @author hal.hildebrand
 * @see EnhancedBubble Reference implementation of orchestrator pattern
 */
public class MultiBubbleSimulation implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MultiBubbleSimulation.class);

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    // Phase 5D: Migration manager
    private final TetrahedralMigration migration;
    private final MigrationLog migrationLog;

    // Phase 5E: Duplicate entity detection
    private final DuplicateEntityDetector duplicateDetector;
    private final DuplicateDetectionConfig duplicateConfig;

    // Component managers
    private final BubbleGridOrchestrator gridOrchestrator;
    private final SimulationExecutionEngine executionEngine;
    private final EntityPopulationManager populationManager;
    private final EntityPhysicsManager physicsManager;
    private final SimulationQueryService queryService;

    // Metrics
    private final SimulationMetrics metrics;

    /**
     * Create a multi-bubble tetrahedral simulation.
     *
     * @param bubbleCount   Number of bubbles to create
     * @param maxLevel      Maximum tree level for bubble distribution (0-21)
     * @param entityCount   Number of entities to spawn
     * @param worldBounds   World coordinate bounds
     * @param behavior      Entity movement behavior
     * @throws IllegalArgumentException if parameters are invalid
     */
    public MultiBubbleSimulation(
        int bubbleCount,
        byte maxLevel,
        int entityCount,
        WorldBounds worldBounds,
        EntityBehavior behavior
    ) {
        if (bubbleCount <= 0) {
            throw new IllegalArgumentException("Bubble count must be positive, got: " + bubbleCount);
        }
        if (maxLevel < 0 || maxLevel > 21) {
            throw new IllegalArgumentException("Max level must be 0-21, got: " + maxLevel);
        }
        if (entityCount <= 0) {
            throw new IllegalArgumentException("Entity count must be positive, got: " + entityCount);
        }

        Objects.requireNonNull(worldBounds, "WorldBounds cannot be null");
        Objects.requireNonNull(behavior, "EntityBehavior cannot be null");

        // Create bubble grid
        var bubbleGrid = new TetreeBubbleGrid(maxLevel);

        // Create spatial index for entity tracking
        var spatialIndex = new Tetree<StringEntityID, EntityDistribution.EntitySpec>(new StringEntityIDGenerator(), 100, maxLevel);

        // Phase 5C: Initialize ghost sync adapter
        var neighborFinder = new TetreeNeighborFinder(spatialIndex);
        var ghostSyncAdapter = new TetreeGhostSyncAdapter(bubbleGrid, neighborFinder);

        // Initialize grid orchestrator
        this.gridOrchestrator = new BubbleGridOrchestrator(bubbleGrid, spatialIndex, ghostSyncAdapter, maxLevel);

        // Metrics and execution engine
        this.executionEngine = new SimulationExecutionEngine();
        this.metrics = new SimulationMetrics();

        // Initialize distribution manager
        var distribution = new EntityDistribution(bubbleGrid, spatialIndex);

        // Initialize population manager
        this.populationManager = new EntityPopulationManager(distribution, entityCount, worldBounds);

        // Initialize physics manager
        this.physicsManager = new EntityPhysicsManager(behavior, worldBounds);

        // Initialize query service
        this.queryService = new SimulationQueryService(bubbleGrid, ghostSyncAdapter, populationManager, metrics);

        // Setup simulation: create bubbles and distribute entities
        initializeSimulation(bubbleCount, entityCount);

        // Phase 5D: Initialize migration log and manager
        this.migrationLog = new MigrationLog();
        this.migration = new TetrahedralMigration(bubbleGrid, spatialIndex);

        // Phase 5E: Initialize duplicate detection
        this.duplicateConfig = DuplicateDetectionConfig.defaultConfig();
        this.duplicateDetector = new DuplicateEntityDetector(bubbleGrid, migrationLog, duplicateConfig);
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.executionEngine.setClock(clock);
    }

    /**
     * Initialize simulation: create bubbles, spawn entities, distribute spatially.
     *
     * @param bubbleCount Number of bubbles to create
     * @param entityCount Number of entities to spawn
     */
    private void initializeSimulation(int bubbleCount, int entityCount) {
        // Step 1: Create bubbles distributed across tree levels
        var maxEntitiesPerBubble = (entityCount / bubbleCount) + 50; // +50 buffer for migration
        gridOrchestrator.createBubbles(bubbleCount, entityCount, maxEntitiesPerBubble);

        // Step 2: Generate entity positions
        var entities = populationManager.populateEntities(entityCount);

        // Step 3: Distribute entities to bubbles spatially
        populationManager.getDistribution().distribute(entities);

        // Step 4: Initialize velocities
        initializeVelocities(entities);
    }

    /**
     * Initialize velocities for all entities based on behavior.
     *
     * @param entities List of entities to initialize
     */
    private void initializeVelocities(List<EntityDistribution.EntitySpec> entities) {
        physicsManager.initializeVelocities(entities);
    }

    /**
     * Start the simulation tick loop.
     *
     * @throws IllegalStateException if already running
     */
    public void start() {
        executionEngine.start(this::tick);
    }

    /**
     * Stop the simulation tick loop.
     */
    public void stop() {
        executionEngine.stop();
    }

    /**
     * Check if simulation is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return executionEngine.isRunning();
    }

    /**
     * Get current tick count.
     *
     * @return Number of ticks executed
     */
    public long getTickCount() {
        return executionEngine.getTickCount();
    }

    /**
     * Get simulation metrics.
     *
     * @return SimulationMetrics instance
     */
    public SimulationMetrics getMetrics() {
        return queryService.getMetrics();
    }

    /**
     * Get migration metrics (Phase 5D).
     *
     * @return TetrahedralMigrationMetrics instance
     */
    public TetrahedralMigrationMetrics getMigrationMetrics() {
        return migration.getMetrics();
    }

    /**
     * Get duplicate detection metrics (Phase 5E).
     *
     * @return DuplicateEntityMetrics instance
     */
    public com.hellblazer.luciferase.simulation.ghost.DuplicateEntityMetrics getDuplicateDetectionMetrics() {
        return duplicateDetector.getMetrics();
    }

    /**
     * Execute one simulation tick: update entities, detect migrations, sync ghosts.
     */
    private void tick() {
        var startTime = executionEngine.getClock().nanoTime();

        try {
            // Increment tick and bucket counters
            var currentTick = executionEngine.incrementTickCount();
            var bucket = executionEngine.incrementBucket();

            // Step 1: Update all bubbles (entity positions and velocities)
            for (var bubble : gridOrchestrator.getBubbleGrid().getAllBubbles()) {
                updateBubbleEntities(bubble, DEFAULT_TICK_INTERVAL_MS / 1000.0f);
            }

            // Step 2: (Phase 5D) Detect and execute migrations
            migration.checkMigrations(currentTick);

            // Step 3: (Phase 5C) Synchronize ghosts across bubble boundaries
            gridOrchestrator.getGhostSyncAdapter().processBoundaryEntities(bucket);
            gridOrchestrator.getGhostSyncAdapter().onBucketComplete(bucket);

            // Step 4: (Phase 5E) Detect and reconcile duplicate entities
            if (duplicateConfig.enabled()) {
                duplicateDetector.detectAndReconcile(gridOrchestrator.getBubbleGrid().getAllBubbles());
            }

            // Step 5: Record metrics
            var elapsedNs = executionEngine.getClock().nanoTime() - startTime;
            var totalEntities = getAllEntities().size();
            metrics.recordTick(elapsedNs, totalEntities);

        } catch (Exception e) {
            // Log error but continue simulation
            log.error("Error in tick {}: {}", executionEngine.getTickCount(), e.getMessage(), e);
        }
    }

    /**
     * Update all entities in a bubble: compute velocities and update positions.
     *
     * @param bubble    Bubble to update
     * @param deltaTime Time step in seconds
     */
    private void updateBubbleEntities(EnhancedBubble bubble, float deltaTime) {
        physicsManager.updateBubbleEntities(bubble, deltaTime);
    }

    /**
     * Get all entities in the simulation (both real and ghosts).
     *
     * @return List of entity snapshots
     */
    public List<? extends SimulationQueryService.EntitySnapshot> getAllEntities() {
        return queryService.getAllEntities();
    }

    /**
     * Get real entities (exclude ghosts).
     * <p>
     * Until Phase 5C, all entities are real (no ghost distinction).
     *
     * @return List of real entity snapshots
     */
    public List<? extends SimulationQueryService.EntitySnapshot> getRealEntities() {
        return queryService.getRealEntities();
    }

    /**
     * Get count of ghost entities.
     * <p>
     * Phase 5C: Returns actual ghost count from ghost sync adapter.
     *
     * @return Number of ghost entities
     */
    public int getGhostCount() {
        return queryService.getGhostCount();
    }

    /**
     * Get a specific bubble by its TetreeKey.
     *
     * @param key TetreeKey of bubble
     * @return EnhancedBubble instance
     * @throws NoSuchElementException if no bubble exists at key
     */
    public EnhancedBubble getBubble(TetreeKey<?> key) {
        return queryService.getBubble(key);
    }

    /**
     * Get all bubbles in the grid.
     *
     * @return Collection of all EnhancedBubbles
     */
    public Collection<EnhancedBubble> getAllBubbles() {
        return queryService.getAllBubbles();
    }

    /**
     * Close simulation and release resources.
     */
    @Override
    public void close() {
        executionEngine.close();
    }
}
