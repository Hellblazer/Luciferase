/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.loop;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Simulation loop for entity movement within a single bubble.
 * <p>
 * Runs a fixed-timestep update loop that:
 * 1. Computes new velocities using EntityBehavior
 * 2. Updates entity positions in the bubble
 * 3. Tracks simulation statistics
 * <p>
 * For Inc 1: Single bubble, single behavior, no distributed sync.
 * Later increments add multi-bubble coordination via BucketScheduler.
 * <p>
 * Thread Safety: SimulationLoop owns the simulation thread and coordinates
 * with behaviors that may have internal state (like FlockingBehavior's
 * velocity cache).
 *
 * @author hal.hildebrand
 */
public class SimulationLoop {

    private static final Logger log = LoggerFactory.getLogger(SimulationLoop.class);

    /**
     * Default tick rate: 60 ticks per second (16.67ms per tick).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    /**
     * Cleanup frequency: every 30 seconds at 60fps.
     */
    private static final long CLEANUP_INTERVAL_TICKS = 1800;

    private final EnhancedBubble bubble;
    private final EntityBehavior behavior;
    private final WorldBounds worldBounds;
    private final long tickIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final Map<String, Vector3f> velocities = new ConcurrentHashMap<>();
    private final SimulationMetrics metrics = new SimulationMetrics();

    private ScheduledFuture<?> tickTask;

    /**
     * Create a simulation loop with default tick rate and world bounds.
     *
     * @param bubble   The bubble to simulate
     * @param behavior The behavior to apply to all entities
     */
    public SimulationLoop(EnhancedBubble bubble, EntityBehavior behavior) {
        this(bubble, behavior, DEFAULT_TICK_INTERVAL_MS, WorldBounds.DEFAULT);
    }

    /**
     * Create a simulation loop with custom tick rate.
     *
     * @param bubble         The bubble to simulate
     * @param behavior       The behavior to apply to all entities
     * @param tickIntervalMs Milliseconds between ticks
     */
    public SimulationLoop(EnhancedBubble bubble, EntityBehavior behavior, long tickIntervalMs) {
        this(bubble, behavior, tickIntervalMs, WorldBounds.DEFAULT);
    }

    /**
     * Create a simulation loop with full customization.
     *
     * @param bubble         The bubble to simulate
     * @param behavior       The behavior to apply to all entities
     * @param tickIntervalMs Milliseconds between ticks
     * @param worldBounds    World boundary configuration
     */
    public SimulationLoop(EnhancedBubble bubble, EntityBehavior behavior,
                          long tickIntervalMs, WorldBounds worldBounds) {
        if (bubble == null) {
            throw new IllegalArgumentException("Bubble cannot be null");
        }
        if (behavior == null) {
            throw new IllegalArgumentException("Behavior cannot be null");
        }
        if (tickIntervalMs <= 0) {
            throw new IllegalArgumentException("Tick interval must be positive");
        }
        if (worldBounds == null) {
            throw new IllegalArgumentException("World bounds cannot be null");
        }

        this.bubble = bubble;
        this.behavior = behavior;
        this.tickIntervalMs = tickIntervalMs;
        this.worldBounds = worldBounds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "SimulationLoop-" + bubble.id());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the simulation loop.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Initialize velocities for all entities
            initializeVelocities();

            tickTask = scheduler.scheduleAtFixedRate(
                this::tick,
                0,
                tickIntervalMs,
                TimeUnit.MILLISECONDS
            );
            log.info("Simulation started: {} entities, {}ms tick interval",
                     bubble.entityCount(), tickIntervalMs);
        }
    }

    /**
     * Stop the simulation loop.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (tickTask != null) {
                tickTask.cancel(false);
                tickTask = null;
            }
            log.info("Simulation stopped after {} ticks. {}", tickCount.get(), metrics);
        }
    }

    /**
     * Shutdown the simulation loop and release resources.
     */
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    /**
     * Check if the simulation is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get the total tick count.
     *
     * @return Number of ticks executed
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get the bubble being simulated.
     *
     * @return EnhancedBubble
     */
    public EnhancedBubble getBubble() {
        return bubble;
    }

    /**
     * Get the behavior being used.
     *
     * @return EntityBehavior
     */
    public EntityBehavior getBehavior() {
        return behavior;
    }

    /**
     * Get simulation metrics.
     *
     * @return SimulationMetrics
     */
    public SimulationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Get the world bounds configuration.
     *
     * @return WorldBounds
     */
    public WorldBounds getWorldBounds() {
        return worldBounds;
    }

    /**
     * Execute a single simulation tick.
     */
    private void tick() {
        try {
            long startNs = System.nanoTime();
            float deltaTime = tickIntervalMs / 1000.0f;

            // Swap velocity buffers for FlockingBehavior (thread-safe double-buffering)
            if (behavior instanceof FlockingBehavior fb) {
                fb.swapVelocityBuffers();
            }

            // Get all entities
            var entities = bubble.getAllEntityRecords();
            var activeEntityIds = entities.stream()
                .map(EnhancedBubble.EntityRecord::id)
                .collect(Collectors.toSet());

            // Periodic cleanup of removed entities from velocity caches
            long currentTick = tickCount.get();
            if (currentTick > 0 && currentTick % CLEANUP_INTERVAL_TICKS == 0) {
                velocities.keySet().retainAll(activeEntityIds);
                if (behavior instanceof FlockingBehavior fb) {
                    fb.cleanupRemovedEntities(activeEntityIds);
                }
                log.debug("Cleanup: {} velocities retained", velocities.size());
            }

            // Update each entity
            for (var entity : entities) {
                var velocity = velocities.computeIfAbsent(entity.id(), k -> new Vector3f());

                // Compute new velocity
                var newVelocity = behavior.computeVelocity(
                    entity.id(),
                    entity.position(),
                    velocity,
                    bubble,
                    deltaTime
                );

                // Store velocity for next tick
                velocities.put(entity.id(), newVelocity);

                // Compute new position
                var newPosition = new Point3f(entity.position());
                newPosition.x += newVelocity.x * deltaTime;
                newPosition.y += newVelocity.y * deltaTime;
                newPosition.z += newVelocity.z * deltaTime;

                // Clamp to world bounds
                newPosition.x = worldBounds.clamp(newPosition.x);
                newPosition.y = worldBounds.clamp(newPosition.y);
                newPosition.z = worldBounds.clamp(newPosition.z);

                // Update position in bubble
                bubble.updateEntityPosition(entity.id(), newPosition);
            }

            // Record metrics
            long frameTimeNs = System.nanoTime() - startNs;
            bubble.recordFrameTime(frameTimeNs);
            metrics.recordTick(frameTimeNs, entities.size());

            tickCount.incrementAndGet();

            // Log periodically (every 10 seconds at 60fps)
            if (currentTick > 0 && currentTick % 600 == 0) {
                log.debug("Tick {}: {} entities, {}", currentTick, entities.size(), metrics);
            }
        } catch (Exception e) {
            log.error("Error in simulation tick: {}", e.getMessage(), e);
        }
    }

    /**
     * Initialize velocities for all entities with random directions.
     */
    private void initializeVelocities() {
        velocities.clear();
        var entities = bubble.getAllEntityRecords();
        var random = new java.util.Random();

        for (var entity : entities) {
            var velocity = new Vector3f(
                (random.nextFloat() - 0.5f) * 2 * behavior.getMaxSpeed(),
                (random.nextFloat() - 0.5f) * 2 * behavior.getMaxSpeed(),
                (random.nextFloat() - 0.5f) * 2 * behavior.getMaxSpeed()
            );
            velocities.put(entity.id(), velocity);
        }

        log.debug("Initialized velocities for {} entities", velocities.size());
    }
}
