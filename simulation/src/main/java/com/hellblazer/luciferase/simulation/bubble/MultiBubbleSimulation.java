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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Main simulation orchestrator for multi-bubble tetrahedral environment.
 * <p>
 * This class manages a distributed 3D simulation with entities partitioned
 * across multiple bubbles in a tetrahedral hierarchy. Key responsibilities:
 * <ul>
 *   <li><b>Bubble Creation</b> - Distribute bubbles across tree levels via {@link TetreeBubbleFactory}</li>
 *   <li><b>Entity Distribution</b> - Assign entities spatially via {@link EntityDistribution}</li>
 *   <li><b>Simulation Loop</b> - Execute behavior updates at 60fps</li>
 *   <li><b>Ghost Synchronization</b> - (Phase 5C) Cross-bubble entity visibility</li>
 *   <li><b>Entity Migration</b> - (Phase 5D) Move entities between bubbles</li>
 * </ul>
 * <p>
 * Architecture (Tetrahedral Model):
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │               MultiBubbleSimulation                             │
 * │                                                                 │
 * │  ┌──────────────────────────────────────────────────────────┐  │
 * │  │         TetreeBubbleGrid (3D hierarchy)                  │  │
 * │  │                                                          │  │
 * │  │  Bubble@L0  ←─────→  8 Bubbles@L1  ←─────→  ...       │  │
 * │  │  (root tet)         (subdivisions)                      │  │
 * │  │                                                          │  │
 * │  │  Each bubble:                                            │  │
 * │  │  - Internal Tetree spatial index                        │  │
 * │  │  - Adaptive bounds (BubbleBounds)                       │  │
 * │  │  - Variable neighbors (4-12)                            │  │
 * │  └──────────────────────────────────────────────────────────┘  │
 * │                                                                 │
 * │  Tick Loop (60fps):                                             │
 * │  1. Update entity positions/velocities (EntityBehavior)         │
 * │  2. Detect migrations (Phase 5D)                                │
 * │  3. Sync ghosts (Phase 5C)                                      │
 * │  4. Record metrics                                              │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * Key Differences from 2D Grid Model:
 * <ul>
 *   <li>No GridConfiguration - use TetreeBubbleGrid</li>
 *   <li>No fixed neighbor count - variable 4-12</li>
 *   <li>RDGCS coordinates instead of 2D (x,y)</li>
 *   <li>Tetrahedral containment vs rectangular boundaries</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class MultiBubbleSimulation implements AutoCloseable {

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    /**
     * Entity snapshot for visualization and queries.
     *
     * @param id        Entity identifier
     * @param position  Current position
     * @param bubbleKey Key of containing bubble
     * @param isGhost   True if this is a ghost entity (not authoritative)
     */
    public record EntitySnapshot(String id, Point3f position, TetreeKey<?> bubbleKey, boolean isGhost) {}

    private final TetreeBubbleGrid bubbleGrid;
    private final Tetree<StringEntityID, EntityDistribution.EntitySpec> spatialIndex;
    private final EntityBehavior behavior;
    private final WorldBounds worldBounds;
    private final int entityCount;
    private final byte maxLevel;

    // Phase 5C: Ghost sync adapter
    private final TetreeGhostSyncAdapter ghostSyncAdapter;
    private final AtomicLong currentBucket;

    // Phase 5D: Migration manager
    private final TetrahedralMigration migration;
    private final MigrationLog migrationLog;

    // Phase 5E: Duplicate entity detection
    private final DuplicateEntityDetector duplicateDetector;
    private final DuplicateDetectionConfig duplicateConfig;

    // Velocity tracking: entityId → velocity
    private final Map<String, Vector3f> velocities;

    // Execution
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    private final AtomicLong tickCount;
    private final SimulationMetrics metrics;
    private volatile Clock clock = Clock.system();
    private ScheduledFuture<?> tickTask;

    // Entity distribution manager
    private final EntityDistribution distribution;

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

        this.maxLevel = maxLevel;
        this.entityCount = entityCount;
        this.worldBounds = Objects.requireNonNull(worldBounds, "WorldBounds cannot be null");
        this.behavior = Objects.requireNonNull(behavior, "EntityBehavior cannot be null");

        // Create bubble grid
        this.bubbleGrid = new TetreeBubbleGrid(maxLevel);

        // Create spatial index for entity tracking
        this.spatialIndex = new Tetree<>(new StringEntityIDGenerator(), 100, maxLevel);

        // Initialize velocity map
        this.velocities = new ConcurrentHashMap<>();

        // Metrics and execution
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.running = new AtomicBoolean(false);
        this.tickCount = new AtomicLong(0);
        this.currentBucket = new AtomicLong(0);
        this.metrics = new SimulationMetrics();

        // Initialize distribution manager
        this.distribution = new EntityDistribution(bubbleGrid, spatialIndex);

        // Setup simulation: create bubbles and distribute entities
        initializeSimulation(bubbleCount, entityCount);

        // Phase 5C: Initialize ghost sync adapter
        var neighborFinder = new TetreeNeighborFinder(spatialIndex);
        this.ghostSyncAdapter = new TetreeGhostSyncAdapter(bubbleGrid, neighborFinder);

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
        this.clock = clock;
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
        TetreeBubbleFactory.createBubbles(bubbleGrid, bubbleCount, maxLevel, maxEntitiesPerBubble);

        // Step 2: Generate entity positions
        var entities = populateEntities(entityCount);

        // Step 3: Distribute entities to bubbles spatially
        distribution.distribute(entities);

        // Step 4: Initialize velocities
        initializeVelocities(entities);
    }

    /**
     * Generate random entity positions within world bounds.
     *
     * @param count Number of entities to create
     * @return List of EntitySpec with random positions
     */
    private List<EntityDistribution.EntitySpec> populateEntities(int count) {
        var entities = new ArrayList<EntityDistribution.EntitySpec>(count);
        var random = new Random(42); // Deterministic seed for reproducibility

        var size = worldBounds.size();
        var min = worldBounds.min();

        for (int i = 0; i < count; i++) {
            // Generate random position in world bounds
            var x = min + random.nextFloat() * size;
            var y = min + random.nextFloat() * size;
            var z = min + random.nextFloat() * size;

            var position = new Point3f(x, y, z);
            var entityId = "entity-" + i;

            // Velocity will be initialized later
            entities.add(new EntityDistribution.EntitySpec(entityId, position, null));
        }

        return entities;
    }

    /**
     * Initialize velocities for all entities based on behavior.
     *
     * @param entities List of entities to initialize
     */
    private void initializeVelocities(List<EntityDistribution.EntitySpec> entities) {
        var random = new Random(43); // Deterministic seed
        var maxSpeed = behavior.getMaxSpeed();

        for (var entity : entities) {
            // Generate random velocity in 3D sphere
            var theta = random.nextFloat() * 2 * Math.PI; // Azimuthal angle
            var phi = random.nextFloat() * Math.PI; // Polar angle
            var speed = random.nextFloat() * maxSpeed;

            var vx = (float) (speed * Math.sin(phi) * Math.cos(theta));
            var vy = (float) (speed * Math.sin(phi) * Math.sin(theta));
            var vz = (float) (speed * Math.cos(phi));

            velocities.put(entity.id(), new Vector3f(vx, vy, vz));
        }
    }

    /**
     * Start the simulation tick loop.
     *
     * @throws IllegalStateException if already running
     */
    public void start() {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("Simulation is already running");
        }

        tickTask = scheduler.scheduleAtFixedRate(
            this::tick,
            0,
            DEFAULT_TICK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stop the simulation tick loop.
     */
    public void stop() {
        if (running.getAndSet(false)) {
            if (tickTask != null) {
                tickTask.cancel(false);
                tickTask = null;
            }
        }
    }

    /**
     * Check if simulation is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get current tick count.
     *
     * @return Number of ticks executed
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get simulation metrics.
     *
     * @return SimulationMetrics instance
     */
    public SimulationMetrics getMetrics() {
        return metrics;
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
        var startTime = clock.nanoTime();

        try {
            // Increment tick and bucket counters
            var currentTick = tickCount.incrementAndGet();
            var bucket = currentBucket.incrementAndGet();

            // Step 1: Update all bubbles (entity positions and velocities)
            for (var bubble : bubbleGrid.getAllBubbles()) {
                updateBubbleEntities(bubble, DEFAULT_TICK_INTERVAL_MS / 1000.0f);
            }

            // Step 2: (Phase 5D) Detect and execute migrations
            migration.checkMigrations(currentTick);

            // Step 3: (Phase 5C) Synchronize ghosts across bubble boundaries
            ghostSyncAdapter.processBoundaryEntities(bucket);
            ghostSyncAdapter.onBucketComplete(bucket);

            // Step 4: (Phase 5E) Detect and reconcile duplicate entities
            if (duplicateConfig.enabled()) {
                duplicateDetector.detectAndReconcile(bubbleGrid.getAllBubbles());
            }

            // Step 5: Record metrics
            var elapsedNs = clock.nanoTime() - startTime;
            var totalEntities = getAllEntities().size();
            metrics.recordTick(elapsedNs, totalEntities);

        } catch (Exception e) {
            // Log error but continue simulation
            System.err.println("Error in tick " + tickCount.get() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Update all entities in a bubble: compute velocities and update positions.
     *
     * @param bubble    Bubble to update
     * @param deltaTime Time step in seconds
     */
    private void updateBubbleEntities(EnhancedBubble bubble, float deltaTime) {
        var entityRecords = bubble.getAllEntityRecords();

        for (var record : entityRecords) {
            var entityId = record.id();
            var currentPos = record.position();
            var currentVel = velocities.get(entityId);

            if (currentVel == null) {
                continue; // Entity has no velocity (shouldn't happen)
            }

            // Compute new velocity based on behavior
            var newVel = behavior.computeVelocity(entityId, currentPos, currentVel, bubble, deltaTime);

            // Update position based on new velocity
            var newPos = new Point3f(currentPos);
            newPos.x += newVel.x * deltaTime;
            newPos.y += newVel.y * deltaTime;
            newPos.z += newVel.z * deltaTime;

            // Clamp to world bounds
            newPos.x = worldBounds.clamp(newPos.x);
            newPos.y = worldBounds.clamp(newPos.y);
            newPos.z = worldBounds.clamp(newPos.z);

            // Update velocity map
            velocities.put(entityId, newVel);

            // Update entity in bubble (this also updates bounds)
            // Note: EnhancedBubble handles position updates internally via its Tetree
            bubble.removeEntity(entityId);
            bubble.addEntity(entityId, newPos, record.content());

            // Note: Spatial index updates are handled by bubble's internal Tetree
            // No need to manually update the top-level spatial index here
        }
    }

    /**
     * Get all entities in the simulation (both real and ghosts).
     *
     * @return List of entity snapshots
     */
    public List<EntitySnapshot> getAllEntities() {
        var snapshots = new ArrayList<EntitySnapshot>();

        // Add real entities from bubbles
        for (var bubble : bubbleGrid.getAllBubbles()) {
            var records = bubble.getAllEntityRecords();
            TetreeKey<?> fallbackKey = null;

            for (var record : records) {
                var key = distribution.getEntityToBubbleMapping().get(record.id());
                if (key == null) {
                    // Entity not in mapping - use fallback key if available
                    if (fallbackKey != null) {
                        key = fallbackKey;
                    } else {
                        // Skip only if no fallback key exists yet
                        continue;
                    }
                }
                if (fallbackKey == null) {
                    fallbackKey = key;
                }
                snapshots.add(new EntitySnapshot(record.id(), record.position(), key, false));
            }

            // Add ghost entities for this bubble
            var ghosts = ghostSyncAdapter.getGhostsForBubble(bubble.id());
            for (var ghost : ghosts) {
                // Determine key for ghost (use fallback or root key)
                var ghostKey = fallbackKey != null ? fallbackKey :
                              com.hellblazer.luciferase.lucien.tetree.TetreeKey.create((byte) 0, 0L, 0L);
                snapshots.add(new EntitySnapshot(
                    ghost.entityId().toString(),
                    ghost.position(),
                    ghostKey,
                    true  // isGhost = true
                ));
            }
        }

        return snapshots;
    }

    /**
     * Get real entities (exclude ghosts).
     * <p>
     * Until Phase 5C, all entities are real (no ghost distinction).
     *
     * @return List of real entity snapshots
     */
    public List<EntitySnapshot> getRealEntities() {
        return getAllEntities().stream()
                               .filter(e -> !e.isGhost())
                               .collect(Collectors.toList());
    }

    /**
     * Get count of ghost entities.
     * <p>
     * Phase 5C: Returns actual ghost count from ghost sync adapter.
     *
     * @return Number of ghost entities
     */
    public int getGhostCount() {
        return ghostSyncAdapter.getTotalGhostCount();
    }

    /**
     * Get a specific bubble by its TetreeKey.
     *
     * @param key TetreeKey of bubble
     * @return EnhancedBubble instance
     * @throws NoSuchElementException if no bubble exists at key
     */
    public EnhancedBubble getBubble(TetreeKey<?> key) {
        return bubbleGrid.getBubble(key);
    }

    /**
     * Get all bubbles in the grid.
     *
     * @return Collection of all EnhancedBubbles
     */
    public Collection<EnhancedBubble> getAllBubbles() {
        return bubbleGrid.getAllBubbles();
    }

    /**
     * Close simulation and release resources.
     */
    @Override
    public void close() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
