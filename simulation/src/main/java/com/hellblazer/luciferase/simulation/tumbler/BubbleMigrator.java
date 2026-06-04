/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.von.Bubble;
import com.hellblazer.luciferase.simulation.von.Manager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Orchestrates bubble migration between servers for load balancing.
 * <p>
 * Migration Protocol (per v4.0 architecture):
 * <ol>
 *   <li>Bubble B selected for migration from Server1 to Server2</li>
 *   <li>Server2 creates new Bubble B' (same bubbleId)</li>
 *   <li>B' sends VON MOVE to neighbors (position unchanged!)</li>
 *   <li>Neighbors update routing: bubbleId → Server2</li>
 *   <li>Server1 deactivates B after confirmation</li>
 *   <li>Ghost sync re-established with new server</li>
 * </ol>
 * <p>
 * Note: Migration does NOT change bubble position - only server assignment.
 * VON neighbors are notified via overlay maintenance protocol.
 */
public class BubbleMigrator {

    private static final Logger log = LoggerFactory.getLogger(BubbleMigrator.class);

    private volatile Clock clock = Clock.system();

    private final SpatialTumbler tumbler;
    private final Duration migrationTimeout;
    private final Duration cooldownPeriod;
    private final int maxConcurrentMigrations;

    // Track in-flight migrations
    private final Map<UUID, MigrationState> inFlightMigrations = new ConcurrentHashMap<>();

    // Cooldown tracking (bubbleId -> last migration time)
    private final Map<UUID, Long> migrationCooldowns = new ConcurrentHashMap<>();

    // Server-specific managers (serverId -> Manager)
    private final Map<UUID, Manager> serverManagers = new ConcurrentHashMap<>();

    // Bubble factory for creating bubbles on target server
    private BiFunction<UUID, Bubble, Bubble> bubbleTransferFactory;

    // Dedicated executor for migration tasks. executeMigration() blocks (it waits for neighbor
    // acknowledgments), so it must NOT run on ForkJoinPool.commonPool() — blocking common-pool
    // workers starves every other async operation in the JVM (Luciferase-0frcy.116). The pool is
    // sized to maxConcurrentMigrations since that is the ceiling of simultaneous in-flight tasks.
    private final ExecutorService migrationExecutor;

    public BubbleMigrator(SpatialTumbler tumbler) {
        this(tumbler, Duration.ofSeconds(1), Duration.ofSeconds(5), 3);
    }

    public BubbleMigrator(SpatialTumbler tumbler, Duration migrationTimeout,
                          Duration cooldownPeriod, int maxConcurrentMigrations) {
        this.tumbler = tumbler;
        this.migrationTimeout = migrationTimeout;
        this.cooldownPeriod = cooldownPeriod;
        this.maxConcurrentMigrations = maxConcurrentMigrations;
        this.migrationExecutor = Executors.newFixedThreadPool(
            Math.max(1, maxConcurrentMigrations),
            r -> {
                var t = new Thread(r, "bubble-migrator");
                t.setDaemon(true);
                return t;
            });
        log.info("BubbleMigrator created: timeout={}ms, cooldown={}ms, maxConcurrent={}",
                 migrationTimeout.toMillis(), cooldownPeriod.toMillis(), maxConcurrentMigrations);
    }

    /**
     * Shut down the dedicated migration executor. Call when the migrator is no longer needed to
     * release its threads.
     */
    public void shutdown() {
        migrationExecutor.shutdownNow();
    }

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Register a Manager for a server.
     */
    public void registerServerManager(UUID serverId, Manager manager) {
        serverManagers.put(serverId, manager);
    }

    /**
     * Set the factory for transferring bubbles between servers.
     * The factory takes (targetServerId, sourceBubble) and returns
     * the new bubble on the target server.
     */
    public void setBubbleTransferFactory(BiFunction<UUID, Bubble, Bubble> factory) {
        this.bubbleTransferFactory = factory;
    }

    /**
     * Execute a single migration.
     *
     * @return CompletableFuture with migration result
     */
    public CompletableFuture<MigrationResult> migrate(Bubble bubble, UUID targetServerId) {
        var bubbleId = bubble.id();

        // Check cooldown (read-only, safe before reservation)
        var lastMigration = migrationCooldowns.get(bubbleId);
        if (lastMigration != null) {
            var elapsed = clock.currentTimeMillis() - lastMigration;
            if (elapsed < cooldownPeriod.toMillis()) {
                return CompletableFuture.completedFuture(
                    new MigrationResult(bubbleId, targetServerId, false,
                                        "In cooldown (" + (cooldownPeriod.toMillis() - elapsed) + "ms remaining)", 0)
                );
            }
        }

        // Luciferase-0frcy.38: atomically reserve the in-flight slot. The previous code did a
        // containsKey() check, a size() check, and a put() as three separate operations on the
        // ConcurrentHashMap — two threads racing on the same bubbleId could both observe
        // containsKey=false and both proceed, the second put() silently overwriting the first
        // (duplicate migration). putIfAbsent makes the duplicate guard atomic.
        long startTime = clock.nanoTime();
        var state = new MigrationState(bubbleId, targetServerId, startTime);
        var existing = inFlightMigrations.putIfAbsent(bubbleId, state);
        if (existing != null) {
            return CompletableFuture.completedFuture(
                new MigrationResult(bubbleId, targetServerId, false, "Already migrating", 0)
            );
        }

        // Check concurrent migration limit AFTER reserving, then back out if we exceeded it. Our
        // own entry is included in size(), so the limit is honored: the (maxConcurrent+1)-th
        // concurrent reservation removes itself and is rejected.
        if (inFlightMigrations.size() > maxConcurrentMigrations) {
            inFlightMigrations.remove(bubbleId, state);
            return CompletableFuture.completedFuture(
                new MigrationResult(bubbleId, targetServerId, false,
                                    "Max concurrent migrations reached", 0)
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeMigration(bubble, targetServerId, startTime);
            } finally {
                // Luciferase-0frcy.38: value-conditional removal so we only clear OUR reservation,
                // never a subsequent re-reservation of the same bubbleId by another thread.
                inFlightMigrations.remove(bubbleId, state);
            }
        }, migrationExecutor) // dedicated pool — never block ForkJoinPool.commonPool (0frcy.116)
          .orTimeout(migrationTimeout.toMillis(), TimeUnit.MILLISECONDS)
          .exceptionally(ex -> {
              // The supplyAsync body's finally already removed our entry on normal completion; this
              // value-conditional removal covers the timeout path (orTimeout completes the stage
              // exceptionally without running the body's finally).
              inFlightMigrations.remove(bubbleId, state);
              return new MigrationResult(bubbleId, targetServerId, false,
                                         "Timeout or error: " + ex.getMessage(), 0);
          });
    }

    /**
     * Execute the actual migration protocol.
     */
    private MigrationResult executeMigration(Bubble sourceBubble, UUID targetServerId, long startTime) {
        var bubbleId = sourceBubble.id();

        log.info("Starting migration of bubble {} to server {}", bubbleId, targetServerId);

        try {
            // Step 1: Create bubble on target server
            if (bubbleTransferFactory == null) {
                return new MigrationResult(bubbleId, targetServerId, false,
                                           "No bubble transfer factory configured", 0);
            }

            var targetBubble = bubbleTransferFactory.apply(targetServerId, sourceBubble);
            if (targetBubble == null) {
                return new MigrationResult(bubbleId, targetServerId, false,
                                           "Failed to create bubble on target server", 0);
            }

            // Step 2: Transfer entities from source to target
            transferEntities(sourceBubble, targetBubble);

            // Step 3: Target bubble sends MOVE to neighbors (position unchanged)
            // This notifies neighbors about the server change
            targetBubble.broadcastMove();

            // Step 4: Wait for neighbor acknowledgments
            // In practice, we'd wait for ACKs - here we use a small delay
            Thread.sleep(50);

            // Step 5: Deactivate source bubble
            sourceBubble.close();

            // Step 6: Update metrics
            var sourceMetrics = tumbler.getServerMetrics(getServerForBubble(sourceBubble));
            var targetMetrics = tumbler.getServerMetrics(targetServerId);

            if (sourceMetrics != null) {
                sourceMetrics.removeBubble(sourceBubble.entityCount());
            }
            if (targetMetrics != null) {
                targetMetrics.addBubble(targetBubble.entityCount());
            }

            // Record cooldown
            migrationCooldowns.put(bubbleId, clock.currentTimeMillis());

            long durationMs = (clock.nanoTime() - startTime) / 1_000_000;
            log.info("Migration complete: bubble {} to server {} in {}ms",
                     bubbleId, targetServerId, durationMs);

            return new MigrationResult(bubbleId, targetServerId, true, "Success", durationMs);

        } catch (Exception e) {
            log.error("Migration failed for bubble {}: {}", bubbleId, e.getMessage(), e);
            return new MigrationResult(bubbleId, targetServerId, false,
                                       "Error: " + e.getMessage(), 0);
        }
    }

    /**
     * Transfer entities from source bubble to target bubble.
     */
    private void transferEntities(Bubble source, Bubble target) {
        // Get all entities from source
        var entities = source.getAllEntityRecords();

        // Add each entity to target
        for (var entity : entities) {
            target.addEntity(entity.id(), entity.position(), entity.content());
        }

        log.debug("Transferred {} entities from {} to {}",
                  entities.size(), source.id(), target.id());
    }

    /**
     * Get the server ID for a bubble.
     * This should be tracked elsewhere; placeholder implementation.
     */
    private UUID getServerForBubble(Bubble bubble) {
        // In real implementation, look up from registry
        return null;
    }

    /**
     * Run a migration cycle based on current load imbalance.
     *
     * @return Number of migrations initiated
     */
    public int runMigrationCycle(Map<UUID, List<Bubble>> serverBubbles) {
        var candidates = tumbler.findMigrationCandidates();
        if (candidates.isEmpty()) {
            return 0;
        }

        int initiated = 0;
        for (var candidate : candidates) {
            var sourceBubbles = serverBubbles.get(candidate.sourceServer());
            if (sourceBubbles == null || sourceBubbles.isEmpty()) {
                continue;
            }

            // Select a bubble to migrate (prefer most loaded)
            var bubble = selectBubbleForMigration(sourceBubbles);
            if (bubble == null) {
                continue;
            }

            migrate(bubble, candidate.targetServer());
            initiated++;

            if (initiated >= maxConcurrentMigrations) {
                break;
            }
        }

        return initiated;
    }

    /**
     * Select a bubble for migration from a list.
     * Prefers bubbles with more entities (higher load contribution).
     */
    private Bubble selectBubbleForMigration(List<Bubble> bubbles) {
        return bubbles.stream()
                      .filter(b -> !inFlightMigrations.containsKey(b.id()))
                      .filter(b -> !isInCooldown(b.id()))
                      .max(Comparator.comparingInt(Bubble::entityCount))
                      .orElse(null);
    }

    private boolean isInCooldown(UUID bubbleId) {
        var lastMigration = migrationCooldowns.get(bubbleId);
        if (lastMigration == null) {
            return false;
        }
        return (clock.currentTimeMillis() - lastMigration) < cooldownPeriod.toMillis();
    }

    /**
     * Get count of in-flight migrations.
     */
    public int inFlightCount() {
        return inFlightMigrations.size();
    }

    /**
     * Clean up stale cooldown entries.
     */
    public void cleanupCooldowns() {
        long now = clock.currentTimeMillis();
        long threshold = cooldownPeriod.toMillis() * 2;
        migrationCooldowns.entrySet().removeIf(e -> (now - e.getValue()) > threshold);
    }

    /**
     * Migration state tracking.
     */
    private record MigrationState(UUID bubbleId, UUID targetServerId, long startTimeNanos) {
    }

    /**
     * Migration result.
     */
    public record MigrationResult(
        UUID bubbleId,
        UUID targetServerId,
        boolean success,
        String message,
        long durationMs
    ) {
    }
}
