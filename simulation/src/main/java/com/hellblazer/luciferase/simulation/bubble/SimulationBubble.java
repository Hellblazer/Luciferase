/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.behavior.CompositeEntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.runtime.Kairos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Prime-Mover event-driven simulation bubble.
 * <p>
 * Replaces timer-based SimulationLoop with @Entity physicsTick() events.
 * Uses the proven VolumeAnimator.AnimationFrame pattern: inner @Entity class
 * with Kronos.sleep() timing and recursive event scheduling.
 * <p>
 * <b>Pattern</b>:
 * <ul>
 *   <li>Orchestrator class (SimulationBubble) with inner @Entity class</li>
 *   <li>Inner class (SimulationBubbleEntity) has physicsTick() event method</li>
 *   <li>Kronos.sleep() for timing, recursive this.physicsTick() for scheduling</li>
 *   <li>@NonEvent on all getter methods</li>
 * </ul>
 * <p>
 * <b>Threading</b>: Single-threaded per bubble in Prime-Mover event loop.
 * <p>
 * <b>Clock Sources</b>:
 * <ul>
 *   <li>Local Clock interface: for deterministic testing (millis/nanos)</li>
 *   <li>Kronos.sleep(): for Prime-Mover event scheduling</li>
 * </ul>
 * <p>
 * <b>Performance Targets</b>:
 * <ul>
 *   <li>TPS: >= 94 TPS (Phase 7A baseline)</li>
 *   <li>Latency p99: < 40ms per tick</li>
 *   <li>Memory: No unbounded growth</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.simulation.loop.SimulationLoop
 * @see com.hellblazer.luciferase.simulation.animation.VolumeAnimator.AnimationFrame
 */
public class SimulationBubble {

    private static final Logger log = LoggerFactory.getLogger(SimulationBubble.class);

    /**
     * Default tick rate: 60 ticks per second (16.67ms per tick).
     * Matches SimulationLoop.DEFAULT_TICK_INTERVAL_MS for compatibility.
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    /**
     * Cleanup frequency: every 30 seconds at 60fps (1800 ticks).
     * Matches SimulationLoop.CLEANUP_INTERVAL_TICKS for consistency.
     */
    private static final long CLEANUP_INTERVAL_TICKS = 1800;

    /**
     * Logging interval: every 10 seconds at 60fps (600 ticks).
     */
    private static final long LOG_INTERVAL_TICKS = 600;

    private final EnhancedBubble           bubble;
    private final EntityBehavior           behavior;
    private final WorldBounds              worldBounds;
    private final com.hellblazer.primeMover.controllers.RealTimeController controller;
    private final SimulationBubbleEntity   entity;
    private       volatile Clock           clock = Clock.system();
    private       volatile boolean         running = false;
    private final Map<String, Vector3f>    velocities = new ConcurrentHashMap<>();
    private final SimulationMetrics        metrics = new SimulationMetrics();

    /**
     * Create a simulation bubble with default tick rate and world bounds.
     *
     * @param bubble   The bubble to simulate
     * @param behavior The behavior to apply to all entities
     */
    public SimulationBubble(EnhancedBubble bubble, EntityBehavior behavior) {
        this(bubble, behavior, DEFAULT_TICK_INTERVAL_MS, WorldBounds.DEFAULT);
    }

    /**
     * Create a simulation bubble with custom tick rate.
     *
     * @param bubble         The bubble to simulate
     * @param behavior       The behavior to apply to all entities
     * @param tickIntervalMs Milliseconds between ticks
     */
    public SimulationBubble(EnhancedBubble bubble, EntityBehavior behavior, long tickIntervalMs) {
        this(bubble, behavior, tickIntervalMs, WorldBounds.DEFAULT);
    }

    /**
     * Create a simulation bubble with full customization.
     *
     * @param bubble         The bubble to simulate
     * @param behavior       The behavior to apply to all entities
     * @param tickIntervalMs Milliseconds between ticks
     * @param worldBounds    World boundary configuration
     */
    public SimulationBubble(EnhancedBubble bubble, EntityBehavior behavior,
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
        this.worldBounds = worldBounds;
        this.controller = new com.hellblazer.primeMover.controllers.RealTimeController("SimBubble-" + bubble.id());
        this.entity = new SimulationBubbleEntity(tickIntervalMs);
        Kairos.setController(controller);

        log.debug("Created SimulationBubble: bubble={}, tickIntervalMs={}, worldBounds={}",
                  bubble.id(), tickIntervalMs, worldBounds);
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
     * Start the simulation bubble.
     * Triggers the initial physicsTick() event and starts the Prime-Mover controller.
     */
    public void start() {
        running = true;
        entity.physicsTick();
        controller.start();
        log.info("Simulation started: {} entities", bubble.entityCount());
    }

    /**
     * Stop the simulation bubble.
     */
    public void stop() {
        running = false;
        controller.stop();
        log.info("Simulation stopped after {} ticks", entity.getTickCount());
    }

    /**
     * Shutdown the simulation bubble and release resources.
     */
    public void shutdown() {
        stop();
        // Prime-Mover controller doesn't have explicit shutdown
    }

    /**
     * Check if the simulation is running.
     *
     * @return true if running
     */
    @NonEvent
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the total tick count.
     *
     * @return Number of ticks executed
     */
    @NonEvent
    public long getTickCount() {
        return entity.getTickCount();
    }

    /**
     * Get the bubble being simulated.
     *
     * @return EnhancedBubble
     */
    @NonEvent
    public EnhancedBubble getBubble() {
        return bubble;
    }

    /**
     * Get the behavior being used.
     *
     * @return EntityBehavior
     */
    @NonEvent
    public EntityBehavior getBehavior() {
        return behavior;
    }

    /**
     * Get the world bounds configuration.
     *
     * @return WorldBounds
     */
    @NonEvent
    public WorldBounds getWorldBounds() {
        return worldBounds;
    }

    /**
     * Get simulation metrics.
     *
     * @return SimulationMetrics
     */
    @NonEvent
    public SimulationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Get the Prime-Mover controller.
     *
     * @return RealTimeController
     */
    @NonEvent
    public com.hellblazer.primeMover.controllers.RealTimeController getController() {
        return controller;
    }

    /**
     * Clean up velocity entries for entities that no longer exist.
     * <p>
     * This prevents memory leaks in long-running simulations where entities
     * are dynamically added and removed. Called automatically every
     * {@link #CLEANUP_INTERVAL_TICKS} ticks by {@link SimulationBubbleEntity#physicsTick()}.
     * <p>
     * Complexity: O(n) where n = number of velocity entries.
     * Thread-safe: Uses ConcurrentHashMap.keySet().retainAll().
     */
    void cleanupRemovedEntities() {
        var activeIds = bubble.getAllEntityRecords().stream()
            .map(EnhancedBubble.EntityRecord::id)
            .collect(java.util.stream.Collectors.toSet());

        int beforeSize = velocities.size();
        velocities.keySet().retainAll(activeIds);
        int afterSize = velocities.size();

        if (beforeSize != afterSize) {
            log.debug("Cleanup: {} velocities retained, {} removed",
                      afterSize, beforeSize - afterSize);
        }
    }

    /**
     * Perform a single physics tick.
     * <p>
     * This method is called from SimulationBubbleEntity.physicsTick() and performs
     * all the actual entity behavior simulation work.
     * <p>
     * <b>Shutdown Semantics</b>: Completes the current tick even if {@link #stop()}
     * is called mid-execution. This ensures entities are left in a consistent state
     * without partial updates. The event loop terminates after the current tick completes.
     *
     * @param deltaTime Time step in seconds
     */
    void performPhysicsTick(float deltaTime) {
        long startNs = clock.nanoTime();

        // 1. Swap velocity buffers for behaviors that support it (thread-safe double-buffering)
        if (behavior instanceof FlockingBehavior fb) {
            fb.swapVelocityBuffers();
        } else if (behavior instanceof CompositeEntityBehavior composite) {
            composite.swapVelocityBuffers();
        }

        // 2. Update each entity
        var entities = bubble.getAllEntityRecords();
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

        // 3. Record metrics
        long frameTimeNs = clock.nanoTime() - startNs;
        bubble.recordFrameTime(frameTimeNs);
        metrics.recordTick(frameTimeNs, entities.size());
    }

    /**
     * Prime-Mover @Entity for physics simulation.
     * <p>
     * Follows VolumeAnimator.AnimationFrame pattern:
     * <ul>
     *   <li>Mutable state for frame/tick tracking</li>
     *   <li>@NonEvent on all getters</li>
     *   <li>physicsTick() as event method</li>
     *   <li>Kronos.sleep() for timing</li>
     *   <li>Recursive this.physicsTick() for continuous execution</li>
     * </ul>
     * <p>
     * Ported logic from SimulationLoop.tick():
     * <ol>
     *   <li>Swap velocity buffers (FlockingBehavior support)</li>
     *   <li>Update each entity (velocity computation, position update, bounds clamping)</li>
     *   <li>Record frame time metrics</li>
     *   <li>Schedule next tick</li>
     * </ol>
     */
    @Entity
    public class SimulationBubbleEntity {
        private final long frameRateNs;
        private       long tickCount = 0;

        public SimulationBubbleEntity(long tickIntervalMs) {
            this.frameRateNs = TimeUnit.NANOSECONDS.convert(tickIntervalMs, TimeUnit.MILLISECONDS);
        }

        @NonEvent
        public long getTickCount() {
            return tickCount;
        }

        /**
         * Execute a single physics tick.
         * <p>
         * Prime-Mover event method that drives entity behavior simulation.
         * Delegates to outer class for actual work, manages timing and recursive scheduling.
         * Follows VolumeAnimator.AnimationFrame.track() pattern exactly.
         */
        public void physicsTick() {
            tickCount++;
            float deltaTime = frameRateNs / 1_000_000_000.0f;

            // Delegate to outer class for actual physics work
            performPhysicsTick(deltaTime);

            // Periodic maintenance
            if (tickCount > 0) {
                // Log periodically (every 10 seconds at 60fps)
                if (tickCount % LOG_INTERVAL_TICKS == 0) {
                    var entityCount = bubble.entityCount();
                    log.debug("Tick {}: {} entities", tickCount, entityCount);
                }

                // Cleanup removed entities (every 30 seconds at 60fps)
                if (tickCount % CLEANUP_INTERVAL_TICKS == 0) {
                    cleanupRemovedEntities();
                }
            }

            // Schedule next tick (recursive event scheduling)
            Kronos.sleep(frameRateNs);
            this.physicsTick();
        }
    }
}
